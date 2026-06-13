package com.zdsj.price

import com.zdsj.affiliate.AffiliateItem
import com.zdsj.affiliate.JdSearchRemedy
import com.zdsj.affiliate.PddLinkParser
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
 * 京东：搜索命中后自动 getNumid 转成数字 SKU 写入 binding，后续走批量 promotiongoodsinfo。
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
     * 解析并返回联盟商品：优先用已绑定 ID；失效时多级关键词搜索 + 详情补价 + 自动数字 SKU。
     */
    @Transactional
    fun resolve(seedName: String, platform: Platform, searchLimit: Int = 10): AffiliateItem {
        val key = normalizeSeedName(seedName)
        val binding = bindingRepo.findBySeedNameAndPlatform(key, platform.code).orElse(null)

        binding?.platformItemId?.let { boundId ->
            tryReuseBinding(key, platform, boundId)?.let { return it }
            log.info("种子绑定失效，重新搜索 seed={} platform={} oldItemId={}", key, platform.code, boundId)
        }

        val candidates = searchWithRecall(platform, key, searchLimit)
        if (candidates.isEmpty()) {
            throw IllegalStateException("搜索无结果: $key (${platform.code})")
        }

        val picked = SeedModelGuard.pickBest(key, candidates)
            ?: throw IllegalStateException("搜索有价但无匹配机型: $key (${platform.code})")
        val stable = normalizeForBinding(platform, picked)
        val priced = refreshPrice(platform, stable.platformItemId, key) ?: stable
        if (!JdSearchRemedy.hasPrice(priced) || isMockItem(priced)) {
            throw IllegalStateException("搜索命中但无价: $key (${platform.code}) itemId=${stable.platformItemId}")
        }
        if (!SeedModelGuard.matchesSeed(key, priced)) {
            throw IllegalStateException("详情与种子型号不一致: $key (${platform.code}) title=${priced.title.take(40)}")
        }
        val normalized = priced.copy(platformItemId = stable.platformItemId)
        upsertBinding(key, platform.code, normalized)
        return normalized
    }

    /** 已绑定 ID 仍有效则直接刷新价格；京东 hash 在 getNumid 不可用时走搜索刷新 */
    private fun tryReuseBinding(seedName: String, platform: Platform, boundId: String): AffiliateItem? {
        val numericId = toDirectPollId(platform, boundId)
        if (numericId != null && numericId.all { it.isDigit() } && platform == Platform.JD) {
            val refreshed = gateway.fetchItem(platform, numericId, bypassCache = true).data ?: return null
            if (!JdSearchRemedy.hasPrice(refreshed) || isMockItem(refreshed)) return null
            if (!SeedModelGuard.matchesSeed(seedName, refreshed)) return null
            val item = refreshed.copy(platformItemId = numericId)
            if (numericId != boundId) {
                log.info("种子绑定升级数字SKU seed={} platform={} {} -> {}", seedName, platform.code, boundId, numericId)
                upsertBinding(seedName, platform.code, item)
            }
            return item
        }
        return refreshBySearch(platform, seedName, boundId)
    }

    /**
     * 多级召回搜索：完整商品名 → 降维关键词 → 品牌+型号（与分享解析/分析页一致）。
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
            if (merged.any { SeedModelGuard.matchesSeed(seedName, it) && !isMockItem(it) }) break
        }
        return merged.filter { JdSearchRemedy.hasPrice(it) && !isMockItem(it) }
    }

    private fun refreshPrice(platform: Platform, itemId: String, seedName: String): AffiliateItem? {
        val numericId = toDirectPollId(platform, itemId)
        if (numericId != null && numericId.all { it.isDigit() } && platform == Platform.JD) {
            val refreshed = gateway.fetchItem(platform, numericId, bypassCache = true).data ?: return null
            return refreshed.takeUnless { isMockItem(it) }?.copy(platformItemId = numericId)
        }
        return refreshBySearch(platform, seedName, itemId)
    }

    /** 按种子名搜索，优先复用已绑定的 platform_item_id */
    private fun refreshBySearch(platform: Platform, seedName: String, preferredId: String): AffiliateItem? {
        val candidates = searchWithRecall(platform, seedName, 10)
        val picked = candidates.firstOrNull {
            it.platformItemId == preferredId && SeedModelGuard.matchesSeed(seedName, it)
        } ?: SeedModelGuard.pickBest(seedName, candidates) ?: return null
        return picked.copy(platformItemId = normalizeForBinding(platform, picked).platformItemId)
    }

    /** 搜索命中后归一化为 binding ID（数字 SKU 优先，getNumid 失败则保留 hash / goods_sign） */
    private fun normalizeForBinding(platform: Platform, item: AffiliateItem): AffiliateItem {
        val stableId = toDirectPollId(platform, item.platformItemId) ?: item.platformItemId
        return item.copy(platformItemId = stableId)
    }

    /** 转为批量直查 ID：京东数字 SKU / 拼多多 goods_sign */
    internal fun toDirectPollId(platform: Platform, itemId: String): String? = when (platform) {
        Platform.JD -> when {
            itemId.all { it.isDigit() } -> itemId
            else -> gateway.resolveNumericItemId(platform, itemId)
        }
        Platform.PDD -> itemId.takeIf { PddLinkParser.isGoodsSign(it) }
        else -> null
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

    private fun rejectMockSource(result: AffiliateResult<List<AffiliateItem>>, platform: Platform) {
        if (affiliateProps.mock) return
        if (result.source == "mock") {
            throw IllegalStateException("联盟 ${platform.code} 仅允许真实数据（mock 已禁用）")
        }
    }

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
}
