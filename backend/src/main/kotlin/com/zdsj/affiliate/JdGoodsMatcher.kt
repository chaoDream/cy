package com.zdsj.affiliate

/**
 * 京东搜索结果与分享标题的匹配打分（短链解析失败时，用「」内标题挑最相关商品）。
 */
object JdGoodsMatcher {

    private val brandAliases = mapOf(
        "小米" to listOf("小米", "xiaomi", "redmi"),
        "苹果" to listOf("苹果", "apple", "iphone"),
        "华为" to listOf("华为", "huawei", "mate", "pura"),
        "vivo" to listOf("vivo"),
        "oppo" to listOf("oppo"),
        "荣耀" to listOf("荣耀", "honor"),
        "三星" to listOf("三星", "samsung"),
    )

    fun pickBest(query: String, candidates: List<AffiliateItem>): AffiliateItem? {
        if (candidates.isEmpty()) return null
        val tokens = tokenize(query)
        if (tokens.isEmpty()) return candidates.first()
        return candidates
            .map { it to score(it, tokens) }
            .filter { (_, s) -> s > 0 }
            .maxByOrNull { it.second }
            ?.first
            ?: candidates.maxByOrNull { score(it, tokens) }
    }

    /** 分享标题与商品标题品牌/型号是否一致（防止有价但错机） */
    fun matchesShareTitle(shareTitle: String, item: AffiliateItem): Boolean {
        val shareBrand = detectBrand(shareTitle) ?: return true
        val itemBrand = detectBrand(item.title) ?: return false
        if (shareBrand != itemBrand) return false
        val tokens = tokenize(shareTitle).filter { it.length >= 3 }
        if (tokens.isEmpty()) return true
        val hits = tokens.count { token -> item.title.contains(token, ignoreCase = true) }
        return hits >= (tokens.size + 1) / 2
    }

    fun detectBrand(text: String): String? {
        val lower = text.lowercase()
        return brandAliases.entries.firstOrNull { (_, aliases) ->
            aliases.any { lower.contains(it) }
        }?.key
    }

    /** 标题中出现的品牌原文（用于构造「vivo S60」类召回词） */
    fun brandWordInTitle(text: String, brandKey: String): String? =
        brandAliases[brandKey]?.firstOrNull { text.contains(it, ignoreCase = true) }

    internal fun tokenize(query: String): List<String> {
        val normalized = query.lowercase()
            .replace(Regex("""[+／/|]"""), " ")
        return normalized.split(Regex("""[\s，,、]+"""))
            .map { it.trim() }
            .filter { it.length >= 2 }
    }

    internal fun score(item: AffiliateItem, tokens: List<String>): Int {
        val title = item.title.lowercase()
        var s = tokens.count { token -> title.contains(token) } * 10
        when (item.shopType) {
            "self" -> s += 8
            "flagship" -> s += 4
        }
        if (title.contains("自营")) s += 4
        // 分享标题里的品牌词必须命中，避免搜出无关机型
        val brandHints = listOf("小米", "xiaomi", "苹果", "iphone", "华为", "mate", "vivo", "oppo", "荣耀", "三星")
        for (brand in brandHints) {
            if (tokens.any { it.contains(brand) || brand.contains(it) }) {
                if (title.contains(brand)) s += 15
                else s -= 20
            }
        }
        return s
    }
}
