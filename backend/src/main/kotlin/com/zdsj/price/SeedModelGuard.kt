package com.zdsj.price

import com.zdsj.affiliate.AffiliateItem
import com.zdsj.affiliate.JdGoodsMatcher
import com.zdsj.affiliate.JdSearchRemedy

/**
 * 种子名与搜索结果的型号门禁：品牌一致 + 关键词命中 + 代际不漂移（如 iPhone 16 不应绑到 17）。
 */
object SeedModelGuard {

    private val generationChecks = listOf(
        Regex("""(?i)iphone\s*(\d+)"""),
        Regex("""(?i)mate\s*(\d+)"""),
        Regex("""(?i)pura\s*(\d+)"""),
        Regex("""(?i)\bx(\d{3})\b"""),
        Regex("""(?i)小米\s*(\d+)"""),
        Regex("""(?i)turbo\s*(\d+)"""),
        Regex("""(?i)\bk(\d+)\b"""),
        Regex("""(?i)iqoo\s*(\d+)"""),
        Regex("""(?i)\bs(\d+)\b"""),
    )

    private val accessoryHints = listOf("笔记本", "电脑", "平板", "保护壳", "充电器", "耳机", "表带", "贴膜")

    fun matchesSeed(seedName: String, item: AffiliateItem): Boolean {
        if (!JdSearchRemedy.hasPrice(item)) return false
        if (!JdGoodsMatcher.matchesShareTitle(seedName, item)) return false
        if (hasGenerationConflict(seedName, item.title)) return false
        if (isAccessoryMismatch(seedName, item.title)) return false
        return true
    }

    fun pickBest(seedName: String, candidates: List<AffiliateItem>): AffiliateItem? {
        val matched = candidates.filter { matchesSeed(seedName, it) }
        if (matched.isEmpty()) return null
        return JdGoodsMatcher.pickBest(seedName, matched)
    }

    internal fun hasGenerationConflict(seed: String, title: String): Boolean =
        generationChecks.any { regex -> generationMismatch(seed, title, regex) }

    private fun generationMismatch(seed: String, title: String, regex: Regex): Boolean {
        val seedGen = regex.find(seed)?.groupValues?.getOrNull(1) ?: return false
        val titleGens = regex.findAll(title).map { it.groupValues[1] }.toList()
        if (titleGens.isEmpty()) return false
        return titleGens.any { it != seedGen }
    }

    /** 种子是手机但命中笔记本/配件等 */
    private fun isAccessoryMismatch(seed: String, title: String): Boolean {
        val seedLower = seed.lowercase()
        val phoneSeed = seedLower.contains("iphone") || seedLower.contains("手机") ||
            listOf("pro max", "ultra", "国行").any { seedLower.contains(it) }
        if (!phoneSeed) return false
        return accessoryHints.any { title.contains(it) }
    }
}
