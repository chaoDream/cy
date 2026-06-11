package com.zdsj.price

import com.zdsj.affiliate.AffiliateItem
import com.zdsj.affiliate.JdSearchRemedy
import com.zdsj.affiliate.Platform
import com.zdsj.affiliate.provider.AffiliateGateway
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
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun normalizeSeedName(name: String): String = name.trim()

    /**
     * 解析并返回联盟商品：优先用已绑定 ID；失效时重新搜索并更新绑定。
     */
    @Transactional
    fun resolve(seedName: String, platform: Platform, searchLimit: Int = 10): AffiliateItem {
        val key = normalizeSeedName(seedName)
        val binding = bindingRepo.findBySeedNameAndPlatform(key, platform.code).orElse(null)

        // 绑定快路：仅当 itemId 为纯数字（可被 fetchItem 直查）时才走。
        // 维易搜索返回的是 hash itemId，fetchItem 只认数字 id 会失败、还会误触熔断堵死后续搜索，
        // 故 hash 绑定直接跳过快路重新搜索；并要求查到的商品有价，避免 mock 兜底的 0 价短路。
        if (binding != null && binding.platformItemId.all { it.isDigit() }) {
            val cached = gateway.fetchItem(platform, binding.platformItemId, bypassCache = true).data
            if (cached != null && JdSearchRemedy.hasPrice(cached)) return cached
            log.info("种子绑定失效，重新搜索 seed={} platform={} oldItemId={}", key, platform.code, binding.platformItemId)
        }

        val keyword = key
        val candidates = gateway.search(platform, keyword, searchLimit).data
            ?: emptyList()
        if (candidates.isEmpty()) {
            throw IllegalStateException("搜索无结果: $keyword (${platform.code})")
        }

        val picked = pickBest(keyword, candidates)
        upsertBinding(key, platform.code, picked)
        return picked
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
