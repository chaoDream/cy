package com.zdsj.affiliate

/**
 * 从京东分享文案/链接中提取 itemId。
 * 支持 item.jd.com 长链、3.cn / u.jd.com 短链、以及无数字 ID 的口令分享。
 */
object JdLinkParser {

    private val itemIdRegex = Regex("""item\.jd\.com/(\d+)\.html""", RegexOption.IGNORE_CASE)
    private val numericIdRegex = Regex("""\d{6,}""")
    private val shareTitleRegex = Regex("""「([^」]+)」""")
    private val urlRegex = Regex(
        """https?://(?:3\.cn|u\.jd\.com|(?:[a-z0-9-]+\.)*jd\.com)/[^\s「」【】]+""",
        RegexOption.IGNORE_CASE,
    )

    fun isJdShareText(text: String): Boolean =
        text.contains("京东", ignoreCase = false) ||
            text.contains("3.cn", ignoreCase = true) ||
            text.contains("jd.com", ignoreCase = true)

    fun extractItemId(linkText: String): String? {
        if (!isJdShareText(linkText)) return null

        itemIdRegex.find(linkText)?.let { return it.groupValues[1] }
        numericIdRegex.find(linkText)?.let { return it.value }

        extractUrl(linkText)?.let { url ->
            extractItemIdFromUrl(url)?.let { return it }
            return stableIdFromShortUrl(url)
        }

        extractShareTitle(linkText)?.let { title ->
            return "jd_title_${title.hashCode().and(0x7FFFFFFF)}"
        }

        return null
    }

    fun extractUrl(text: String): String? =
        urlRegex.find(text)?.value?.trimEnd('，', '。', ',', '.', ';', '；')

    fun extractShareTitle(text: String): String? =
        shareTitleRegex.find(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    /** 降维召回关键词（剔除颜色/容量/版本等规格词）：标题优先，其次整段文案 */
    fun extractKeyword(linkText: String): String? =
        KeywordDegrader.degrade(extractShareTitle(linkText) ?: linkText)

    /** 从 URL path/query 提取数字 SKU（短链跳转前有时已带 sku） */
    fun extractItemIdFromUrl(url: String): String? {
        itemIdRegex.find(url)?.let { return it.groupValues[1] }
        Regex("""[?&](?:sku|skuId|id)=(\d+)""", RegexOption.IGNORE_CASE)
            .find(url)?.let { return it.groupValues[1] }
        return numericIdRegex.find(url)?.value
    }

    /** 短链/口令无数字 ID 时，用 path token 生成稳定 mock ID */
    fun stableIdFromShortUrl(url: String): String {
        val withoutQuery = url.substringBefore('?').substringBefore('#')
        val token = withoutQuery.substringAfter("://").substringAfter('/').ifBlank { url.hashCode().toString() }
        return "jd_short_$token"
    }
}
