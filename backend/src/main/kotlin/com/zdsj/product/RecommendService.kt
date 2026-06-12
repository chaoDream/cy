package com.zdsj.product

import com.zdsj.affiliate.Platform
import com.zdsj.affiliate.jd.JdUnionService
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
    private val jdUnionService: JdUnionService,
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
        val JINGFEN_ELITE_IDS = listOf(22, 24) // 实时畅销榜, 数码电器
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

        // 2. JD Jingfen (curated high-commission products)
        for (eliteId in JINGFEN_ELITE_IDS) {
            val items = runCatching { jdUnionService.jingfen(eliteId, 10) }.getOrDefault(emptyList())
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
                        "activityTags" to affiliate.activityTags,
                    ),
                )
            }
        }

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
                            "activityTags" to affiliate.activityTags,
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
}

@RestController
@RequestMapping("/api/product")
class RecommendController(private val recommendService: RecommendService) {

    @GetMapping("/recommend")
    fun recommend(): ApiResponse<List<Map<String, Any?>>> =
        ApiResponse.ok(recommendService.recommend())
}
