package com.zdsj.affiliate

import java.math.BigDecimal

/**
 * 京东联盟无价/403 时的搜索补救：统一召回词生成与候选筛选。
 * 用于 Veapi fetchJdItem fallback、分享解析、分析页刷新、链接解析落库。
 */
object JdSearchRemedy {

    private val modelPatterns = listOf(
        Regex("""(?i)iphone\s*\d+(?:\s*(?:pro\s*max|pro|plus|max))?"""),
        Regex("""(?i)mate\s*\d+(?:\s*(?:pro\+?|rs))?"""),
        Regex("""(?i)pura\s*\d+"""),
        Regex("""(?i)iqoo\s*\d+"""),
        Regex("""(?i)\bs\d+\b"""),
        Regex("""(?i)小米\s*\d+(?:\s*(?:pro\s*max|pro|ultra|max))?"""),
        Regex("""(?i)redmi\s*[\w\d]+"""),
        Regex("""(?i)x\d{3}(?:\s*(?:pro\+?|ultra))?"""),
    )

    /** 召回词：完整标题 → KeywordDegrader → 品牌+型号 → 数字 SKU */
    fun recallKeywords(shareTitle: String?, numericSku: String? = null): List<String> {
        val queries = mutableListOf<String>()
        shareTitle?.takeIf { it.isNotBlank() }?.let { queries += it }
        shareTitle?.let { KeywordDegrader.degrade(it) }?.let { queries += it }
        shareTitle?.let { brandModelKeyword(it) }?.let { queries += it }
        numericSku?.takeIf { it.all(Char::isDigit) }?.let { queries += it }
        return queries.distinct().filter { it.isNotBlank() }
    }

    /** 从分享标题提取「品牌 + 型号」级召回词，如 vivo S60、iPhone 17 Pro */
    fun brandModelKeyword(title: String): String? {
        if (title.isBlank()) return null
        val brandKey = JdGoodsMatcher.detectBrand(title) ?: return null
        val brandWord = JdGoodsMatcher.brandWordInTitle(title, brandKey) ?: return null
        val model = modelPatterns.asSequence()
            .mapNotNull { it.find(title)?.value?.trim() }
            .firstOrNull()
            ?: return null
        return "$brandWord $model"
    }

    /** 分享标题门禁 + 有价优先 */
    fun pickPricedMatch(shareTitle: String, hits: List<AffiliateItem>): AffiliateItem? {
        if (hits.isEmpty()) return null
        return hits.firstOrNull {
            hasPrice(it) && JdGoodsMatcher.matchesShareTitle(shareTitle, it)
        } ?: JdGoodsMatcher.pickBest(shareTitle, hits)
            ?.takeIf { hasPrice(it) && JdGoodsMatcher.matchesShareTitle(shareTitle, it) }
    }

    /** 数字 SKU 搜索补救：优先匹配同 SKU，否则取首个有价候选 */
    fun pickPricedBySku(hits: List<AffiliateItem>, numericSku: String): AffiliateItem? {
        if (hits.isEmpty()) return null
        hits.firstOrNull { hasPrice(it) && it.platformItemId == numericSku }?.let { return it }
        hits.firstOrNull { hasPrice(it) && it.couponInfo["_jdSkuId"] == numericSku }?.let { return it }
        return hits.firstOrNull { hasPrice(it) }
    }

    fun hasPrice(item: AffiliateItem): Boolean =
        item.rawPrice.compareTo(BigDecimal.ZERO) > 0
}
