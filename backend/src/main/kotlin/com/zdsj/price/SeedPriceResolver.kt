package com.zdsj.price

import com.zdsj.affiliate.AffiliateItem
import com.zdsj.affiliate.JdSearchRemedy
import com.zdsj.affiliate.Platform
import com.zdsj.affiliate.provider.AffiliateGateway
import com.zdsj.affiliate.provider.AffiliateResult
import com.zdsj.config.AffiliateProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

/**
 * 按商品名称在指定平台搜索并绑定稳定 itemId，供定时采价复用。
 */
@Service
class SeedPriceResolver(
    private val gateway: AffiliateGateway,
    private val bindingRepo: PriceSeedBindingRepository,
    private val affiliateProps: AffiliateProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun normalizeSeedName(name: String): String = name.trim()

    /**
     * 解析并返回联盟商品：优先用已绑定 ID；失效时多级关键词搜索 + 详情补价。
     */
    @Transactional
    fun resolve(seedName: String, platform: Platform, searchLimit: Int = 10): AffiliateItem {
        val key = normalizeSeedName(seedName)
        val binding = bindingRepo.findBySeedNameAndPlatform(key, platform.code).orElse(null)

        // 绑定快路：纯数字 SKU 可走 fetchItem（promotiongoodsinfo + jd_search 补救）
        if (binding != null && binding.platformItemId.all { it.isDigit() }) {
            val cached = refreshPrice(platform, binding.platformItemId)
            if (cached != null && JdSearchRemedy.hasPrice(cached) && !isMockItem(cached)) return cached
            log.info("种子绑定失效，重新搜索 seed={} platform={} oldItemId={}", key, platform.code, binding.platformItemId)
        }

        val candidates = searchWithRecall(platform, key, searchLimit)
        if (candidates.isEmpty()) {
            throw IllegalStateException("搜索无结果: $key (${platform.code})")
        }

        val picked = pickBest(key, candidates)
        val priced = refreshPrice(platform, picked.platformItemId) ?: picked
        if (!JdSearchRemedy.hasPrice(priced) || isMockItem(priced)) {
            throw IllegalStateException("搜索命中但无价: $key (${platform.code}) itemId=${picked.platformItemId}")
        }
        upsertBinding(key, platform.code, priced)
        return priced
    }

    /**
     * 多级召回搜索：完整商品名 → 降维关键词 → 品牌+型号（与分享解析/分析页一致）。
     * 合并多轮结果后再筛选有价商品，避免「12GB+256GB 国行」一类长词一次搜空就失败。
     */
    private fun searchWithRecall(platform: Platform, seedName: String, searchLimit: Int): List<AffiliateItem> {
        val seen = linkedSetOf<String>()
        val merged = mutableListOf<AffiliateItem>()
        for (keyword in JdSearchRemedy.recallKeywords(seedName)) {
            val searchResult = gateway.search(platform, keyword, searchLimit)
            rejectMockSource(searchResult, platform)
            for (item in searchResult.data.orEmpty()) {
                val dedupeKey = "${item.platform}_${item.platformItemId}"
                if (seen.add(dedupeKey)) merged.add(item)
            }
            if (merged.any { JdSearchRemedy.hasPrice(it) && !isMockItem(it) }) break
        }
        return merged.filter { JdSearchRemedy.hasPrice(it) && !isMockItem(it) }
    }

    /** 搜索命中后走 fetchItem 补全/刷新价格（promotiongoodsinfo + 搜索补救） */
    private fun refreshPrice(platform: Platform, itemId: String): AffiliateItem? {
        val refreshed = gateway.fetchItem(platform, itemId, bypassCache = true).data ?: return null
        return refreshed.takeUnless { isMockItem(it) }
    }

    private fun upsertBinding(seedName: String, platform: String, item: AffiliateItem) {
        val entity = bindingRepo.findBySeedNameAndPlatform(seedName, platform).orElseGet { PriceSeedBinding() }
        entity.seedName = seedName
        entity.platform = platform
        entity.platformItemId = item.platformItemId
        entity.resolvedTitle = item.title
        entity.updatedAt = OffsetDateTime.now()
        bindingRepo.save(entity)
    }

    fun touchBinding(seedName: String, platform: String, rawProductId: Long) {
        val key = normalizeSeedName(seedName)
        bindingRepo.findBySeedNameAndPlatform(key, platform).ifPresent { b ->
            b.rawProductId = rawProductId
            b.lastPolledAt = OffsetDateTime.now()
            b.updatedAt = OffsetDateTime.now()
            bindingRepo.save(b)
        }
    }

    /** 生产关闭 mock 时拒绝 mock 数据源（降级链兜底或历史假绑定）。 */
    private fun rejectMockSource(result: AffiliateResult<List<AffiliateItem>>, platform: Platform) {
        if (affiliateProps.mock) return
        if (result.source == "mock") {
            throw IllegalStateException("联盟 ${platform.code} 仅允许真实数据（mock 已禁用）")
        }
    }

    /** MockCatalog 生成的 itemId 形如 jd_iphone16pro / pdd_mate70pro，与真实 SKU / goodsSign 区分。 */
    internal fun isMockItem(item: AffiliateItem): Boolean {
        if (affiliateProps.mock) return false
        val id = item.platformItemId
        val suffix = when {
            id.startsWith("jd_") -> id.removePrefix("jd_")
            id.startsWith("pdd_") -> id.removePrefix("pdd_")
            else -> return item.title.startsWith("Mock商品")
        }
        return suffix.isNotEmpty() && !suffix.all { it.isDigit() }
    }

    /** 标题匹配分 + 自营/旗舰店优先 */
    internal fun pickBest(keyword: String, candidates: List<AffiliateItem>): AffiliateItem {
        val tokens = keyword.lowercase()
            .split(Regex("""[\s，,、/|]+"""))
            .map { it.trim() }
            .filter { it.length >= 2 }
        return candidates.maxBy { scoreCandidate(it, tokens) }
    }

    private fun scoreCandidate(item: AffiliateItem, tokens: List<String>): Int {
        val title = item.title.lowercase()
        var score = tokens.count { title.contains(it) } * 10
        when (item.shopType) {
            "self" -> score += 8
            "flagship" -> score += 5
        }
        if (title.contains("自营")) score += 4
        if (title.contains("百亿补贴")) score += 2
        return score
    }
}
