package com.zdsj.affiliate

/**
 * 从拼多多分享文案/链接中提取 itemId。
 * 支持 goods_id 数字、mobile.yangkeduo.com?ps= 短链参数等。
 */
object PddLinkParser {

    private val numericIdRegex = Regex("""\d{6,}""")
    private val psParamRegex = Regex("""[?&]ps=([^&\s#]+)""", RegexOption.IGNORE_CASE)
    private val goodsIdRegex = Regex("""[?&]goods_id=(\d+)""", RegexOption.IGNORE_CASE)
    private val shareTitleRegex = Regex("""「([^」]+)」""")

    fun isPddShareText(text: String): Boolean =
        text.contains("pinduoduo", ignoreCase = true) ||
            text.contains("yangkeduo", ignoreCase = true) ||
            text.contains("拼多多")

    fun extractItemId(linkText: String): String? {
        if (!isPddShareText(linkText)) return null

        goodsIdRegex.find(linkText)?.let { return it.groupValues[1] }
        numericIdRegex.find(linkText)?.let { return it.value }
        psParamRegex.find(linkText)?.let { return "pdd_ps_${it.groupValues[1]}" }

        extractUrl(linkText)?.let { url ->
            goodsIdRegex.find(url)?.let { return it.groupValues[1] }
            psParamRegex.find(url)?.let { return "pdd_ps_${it.groupValues[1]}" }
        }

        extractShareTitle(linkText)?.let { title ->
            return "pdd_title_${title.hashCode().and(0x7FFFFFFF)}"
        }

        return "pdd_default"
    }

    /** 多多进宝 goodsSign（加密 goodsId），非内部占位 id */
    fun isGoodsSign(itemId: String): Boolean =
        !itemId.startsWith("pdd_") && itemId.contains('_') && itemId.length >= 20

    /** 由 pdd_ps_ 占位 id 还原 yangkeduo 短链 URL */
    fun psToUrl(itemId: String): String? {
        if (!itemId.startsWith("pdd_ps_")) return null
        val ps = itemId.removePrefix("pdd_ps_")
        if (ps.isBlank()) return null
        return "https://mobile.yangkeduo.com/goods1.html?ps=$ps"
    }

    /** 供搜索 API 使用的关键词：优先链接，其次标题 */
    fun toSearchKeyword(linkText: String): String? =
        extractUrl(linkText) ?: extractShareTitle(linkText)

    /** 降维召回关键词（剔除颜色/容量/版本等规格词）：标题优先，其次整段文案 */
    fun extractKeyword(linkText: String): String? =
        KeywordDegrader.degrade(extractShareTitle(linkText) ?: linkText)

    fun extractUrl(text: String): String? =
        Regex("""https?://[^\s「」【】]+""").find(text)?.value
            ?.trimEnd('，', '。', ',', '.', ';', '；')

    fun extractShareTitle(text: String): String? =
        shareTitleRegex.find(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
}
