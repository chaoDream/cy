package com.zdsj.product

import com.zdsj.affiliate.ActivityTags
import com.zdsj.affiliate.AffiliateItem
import com.zdsj.affiliate.Platform
import com.zdsj.affiliate.provider.AffiliateGateway
import com.zdsj.common.ApiResponse
import com.zdsj.rank.RankService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

@Service
class RecommendService(
    private val rankService: RankService,
    private val gateway: AffiliateGateway,
    private val ingestService: ProductIngestService,
    private val imageStorage: ProductImageStorageService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile
    private var cache: List<Map<String, Any?>> = emptyList()

    @Volatile
    private var cacheTime: Long = 0L

    private companion object {
        const val CACHE_TTL_MS = 30 * 60 * 1000L // 30 min
        val HOT_KEYWORDS = listOf("手机", "iPhone", "华为手机", "小米手机")
        /** 京东 goods.recommend.get：1=猜你喜欢，2=实时热销（首页好物推荐主来源） */
        val JD_MATERIAL_ELITE_IDS = listOf(1, 2)
        /** 京东京粉精选（高佣补充）：22=实时畅销榜，24=数码家电 */
        val JD_JINGFEN_ELITE_IDS = listOf(22, 24)
        /** 拼多多 pdd_recommend：4=猜你喜欢，5=实时热销榜 */
        val PDD_MATERIAL_CHANNEL_TYPES = listOf(4, 5)
    }

    fun recommend(): List<Map<String, Any?>> {
        val now = System.currentTimeMillis()
        if (cache.isNotEmpty() && now - cacheTime < CACHE_TTL_MS) return cache

        val seen = ConcurrentHashMap.newKeySet<String>()
        val result = mutableListOf<Map<String, Any?>>()

        // 1. Rank data (already analyzed products with real prices)
        val rankItems = runCatching { rankService.phoneRank(null, null, null) }.getOrDefault(emptyList())
        for (item in rankItems) {
            val key = "${item["platform"]}_${item["platformItemId"]}"
            if (seen.add(key)) result.add(item)
        }

        // 2a. 千人千面 / 猜你喜欢（goods.recommend.get → 维易 jd_materialquery）
        appendMaterialRecommend(Platform.JD, JD_MATERIAL_ELITE_IDS, seen, result)
        appendMaterialRecommend(Platform.PDD, PDD_MATERIAL_CHANNEL_TYPES, seen, result)

        // 2b. 京粉精选高佣补充（jingfen.query，与 recommend 的 eliteId 语义不同）
        appendCurated(Platform.JD, JD_JINGFEN_ELITE_IDS, seen, result)

        // 3. Hot keyword search across platforms
        for (keyword in HOT_KEYWORDS) {
            for (platform in Platform.entries) {
                val items = runCatching {
                    gateway.search(platform, keyword, 5).data ?: emptyList()
                }.getOrDefault(emptyList())
                for (affiliate in items) {
                    val key = "${affiliate.platform}_${affiliate.platformItemId}"
                    if (!seen.add(key)) continue
                    val raw = runCatching { ingestService.upsert(affiliate) }.getOrNull() ?: continue
                    result.add(
                        mapOf(
                            "platform" to affiliate.platform,
                            "platformItemId" to affiliate.platformItemId,
                            "title" to affiliate.title,
                            "imageUrl" to imageStorage.displayUrl(raw),
                            "bestFinalPrice" to affiliate.rawPrice.takeIf { it > BigDecimal.ZERO },
                            "shopName" to affiliate.shopName,
                            "activityTags" to ActivityTags.sanitize(affiliate.activityTags),
                        ),
                    )
                }
            }
        }

        val final = result.filter {
            val price = it["bestFinalPrice"] as? BigDecimal
            price != null && price > BigDecimal.ZERO
        }

        cache = final
        cacheTime = now
        log.info("[recommend] refreshed: {} items (rank={}, total={})", final.size, rankItems.size, result.size)
        return final
    }

    private fun appendMaterialRecommend(
        platform: Platform,
        channelIds: List<Int>,
        seen: MutableSet<String>,
        result: MutableList<Map<String, Any?>>,
    ) {
        for (channelId in channelIds) {
            val items = runCatching {
                gateway.fetchMaterialRecommend(platform, channelId, 10).data ?: emptyList()
            }.getOrDefault(emptyList())
            appendAffiliateItems(items, seen, result)
        }
    }

    private fun appendCurated(
        platform: Platform,
        channelIds: List<Int>,
        seen: MutableSet<String>,
        result: MutableList<Map<String, Any?>>,
    ) {
        for (channelId in channelIds) {
            val items = runCatching {
                gateway.fetchEliteGoods(platform, channelId, 10).data ?: emptyList()
            }.getOrDefault(emptyList())
            appendAffiliateItems(items, seen, result)
        }
    }

    private fun appendAffiliateItems(
        items: List<AffiliateItem>,
        seen: MutableSet<String>,
        result: MutableList<Map<String, Any?>>,
    ) {
        for (affiliate in items) {
            val key = "${affiliate.platform}_${affiliate.platformItemId}"
            if (!seen.add(key)) continue
            val raw = runCatching { ingestService.upsert(affiliate) }.getOrNull() ?: continue
            result.add(
                mapOf(
                    "platform" to affiliate.platform,
                    "platformItemId" to affiliate.platformItemId,
                    "title" to affiliate.title,
                    "imageUrl" to imageStorage.displayUrl(raw),
                    "bestFinalPrice" to affiliate.rawPrice.takeIf { it > BigDecimal.ZERO },
                    "shopName" to affiliate.shopName,
                    "activityTags" to ActivityTags.sanitize(affiliate.activityTags),
                ),
            )
        }
    }
}

@RestController
@RequestMapping("/api/product")
class RecommendController(private val recommendService: RecommendService) {

    @GetMapping("/recommend")
    fun recommend(): ApiResponse<List<Map<String, Any?>>> =
        ApiResponse.ok(recommendService.recommend())
}
