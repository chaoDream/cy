package com.zdsj.sku

/**
 * 标题 → 标准属性解析。
 * 品牌：京东 platformBrand > 目录库别名 > 内置兜底；
 * 型号：内置正则 > 目录库长匹配 > 标题启发式推断。
 */
class SkuTitleParser {

    private val storageRegex = Regex("""(\d+)\s?(GB|G|TB|T)\b""", RegexOption.IGNORE_CASE)
    private val refurbishWords = listOf("翻新", "官翻", "二手", "9成新", "95新", "准新")
    private val packageWords = listOf("套装", "礼盒", "全家桶", "保护壳套餐", "充电器套装")
    private val nonRegionWords = listOf("港版", "美版", "日版", "外版", "海外版", "有锁")
    private val unsealedWords = listOf("未拆封", "未激活", "无锁")

    fun parse(title: String, platformBrand: String?, catalog: SkuCatalogSnapshot): SkuParseResult {
        val lower = title.lowercase()
        val brand = resolveBrand(platformBrand, lower, catalog)
        val storage = storageRegex.find(title)?.value?.uppercase()?.replace(" ", "")
        val model = extractModel(title, brand, catalog)
        val regionVersion = if (nonRegionWords.any { title.contains(it) }) "非国行" else "国行"
        val condition = when {
            refurbishWords.any { title.contains(it) } -> "翻新/二手"
            else -> "全新"
        }
        val packageType = if (packageWords.any { title.contains(it) }) "套装" else "裸机"

        val structuralRiskTags = buildList {
            if (regionVersion == "非国行") add("疑似非国行")
            if (condition == "翻新/二手") add("疑似翻新二手")
            if (packageType == "套装") add("套装不可直接比价")
            if (unsealedWords.any { title.contains(it) }) add("售后不确定")
            if (brand == null || model == null) add("暂不在标准型号库")
        }

        val confidence = when {
            brand != null && model != null && storage != null &&
                structuralRiskTags.none { it == "暂不在标准型号库" } -> "high"
            brand != null && model != null &&
                structuralRiskTags.none { it == "暂不在标准型号库" } -> "mid"
            structuralRiskTags.isNotEmpty() -> "low"
            else -> "mid"
        }

        val standardName = listOfNotNull(brand, model, storage, regionVersion.takeIf { it == "非国行" })
            .joinToString(" ")
            .ifBlank { title.take(40) }

        return SkuParseResult(
            brand = brand, series = null, model = model, storage = storage, color = null,
            networkVersion = if (title.contains("5G")) "5G" else null,
            regionVersion = regionVersion, condition = condition, packageType = packageType,
            standardName = standardName, confidence = confidence, riskTags = structuralRiskTags,
        )
    }

    fun normalizePlatformBrand(raw: String?, catalog: SkuCatalogSnapshot): String? {
        val name = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val lower = name.lowercase()
        catalog.brandAliasToCanonical[lower]?.let { return it }
        catalog.brandAliasToCanonical.entries.firstOrNull { lower.contains(it.key) }?.value?.let { return it }
        return name
    }

    fun resolveBrand(platformBrand: String?, titleLower: String, catalog: SkuCatalogSnapshot): String? {
        normalizePlatformBrand(platformBrand, catalog)?.let { return it }
        catalog.brandAliasToCanonical.entries
            .sortedByDescending { it.key.length }
            .firstOrNull { titleLower.contains(it.key) }
            ?.value
            ?.let { return it }
        return null
    }

    fun extractModel(title: String, brand: String?, catalog: SkuCatalogSnapshot): String? {
        builtinModelPatterns().firstNotNullOfOrNull { it.find(title)?.value?.trim() }?.let { return it }
        if (brand != null) {
            catalog.modelsByBrand[brand].orEmpty()
                .firstOrNull { model -> title.contains(model, ignoreCase = true) }
                ?.let { return it }
            inferModelFromTitle(title, brand)?.let { return it }
        }
        return null
    }

    /** 从标题中品牌词之后、容量规格之前截取型号片段（供目录同步学习新型号） */
    fun inferModelFromTitle(title: String, brand: String): String? {
        val brandWord = findBrandWordInTitle(title, brand) ?: return null
        val after = title.substringAfter(brandWord, "").trim()
        if (after.isBlank()) return null
        val tokens = after.split(Regex("""[\s+/,，、]+""")).filter { it.isNotBlank() }
        val modelTokens = mutableListOf<String>()
        for (tok in tokens) {
            if (storageRegex.containsMatchIn(tok) ||
                Regex("""\d+\s*(GB|G|TB|T)""", RegexOption.IGNORE_CASE).containsMatchIn(tok) ||
                Regex("""^\d+[gG]$""").matches(tok)
            ) break
            if (tok.length == 1 && !tok[0].isDigit()) continue
            if (tok.equals("5g", ignoreCase = true)) continue
            modelTokens.add(tok)
            if (modelTokens.size >= 4) break
        }
        val model = modelTokens.joinToString(" ").trim()
        return model.takeIf { it.length >= 2 }
    }

    private fun findBrandWordInTitle(title: String, brand: String): String? {
        val idx = title.indexOf(brand, ignoreCase = true)
        if (idx >= 0) return title.substring(idx, idx + brand.length)
        val aliases = when (brand) {
            "一加" -> listOf("OnePlus", "oneplus")
            "Apple" -> listOf("iPhone", "Apple", "苹果")
            "三星" -> listOf("Galaxy", "SAMSUNG")
            else -> emptyList()
        }
        for (alias in aliases) {
            val i = title.indexOf(alias, ignoreCase = true)
            if (i >= 0) return title.substring(i, i + alias.length)
        }
        return null
    }

    private fun builtinModelPatterns(): List<Regex> = listOf(
        Regex("""iPhone\s?\d+\s?(Pro\s?Max|Pro|Plus|e)?""", RegexOption.IGNORE_CASE),
        Regex("""Mate\s?\d+\s?(Pro\+?|RS|保时捷)?""", RegexOption.IGNORE_CASE),
        Regex("""Pura\s?\d+\s?(Pro\+?|Ultra)?""", RegexOption.IGNORE_CASE),
        Regex("""(小米|Xiaomi|Redmi)\s?\d+\s?(Pro\s?Max|Pro|Ultra|MAX)?""", RegexOption.IGNORE_CASE),
        Regex("""(一加|OnePlus)\s?Ace\s?\d+\s?(Pro\+?|Pro|Max)?""", RegexOption.IGNORE_CASE),
        Regex("""(一加|OnePlus)\s?\d+\s?(Pro\s?Max|Pro|R)?""", RegexOption.IGNORE_CASE),
        Regex("""X\d{3}\s?(Pro\+?|Ultra)?""", RegexOption.IGNORE_CASE),
        Regex("""Find\s?X\d+\s?(Pro\+?|Ultra)?""", RegexOption.IGNORE_CASE),
        Regex("""Galaxy\s?S\d+\s?(Ultra\+?|Ultra|\+)?""", RegexOption.IGNORE_CASE),
    )
}
