package com.zdsj.affiliate

/**
 * 关键词降维：把分享文案/标题降为「品牌 + 品类/型号」级别的召回词，
 * 剔除颜色 / 容量 / 版本 / 自营等规格词，避免「精准匹配原 SKU」的比价回链。
 *
 * 例：`Apple iPhone 17 256GB 黑色 自营 全网通5G` → `Apple iPhone 17`
 */
object KeywordDegrader {

    /** 颜色 / 容量 / 版本 / 渠道等规格修饰词，召回时一律剔除 */
    private val specWords = listOf(
        // 颜色
        "白色", "黑色", "金色", "银色", "蓝色", "绿色", "紫色", "粉色", "红色", "灰色",
        "钛金属", "原色钛", "沙漠色", "深空", "午夜", "星光", "暗夜",
        // 容量
        "128g", "256g", "512g", "1t", "1tb", "64g", "128gb", "256gb", "512gb",
        // 版本 / 渠道
        "自营", "国行", "官方", "旗舰", "全网通", "5g", "套装", "标准版", "促销", "正品",
        "未拆封", "全新", "现货", "包邮", "百亿补贴",
    )

    private val bracketContent = Regex("""[「【\[]([^」】\]]+)[」】\]]""")
    private val urlRegex = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
    private val capacityRegex = Regex("""\d+\s*(?:gb?|tb?)""", RegexOption.IGNORE_CASE)
    private val multiSpace = Regex("""\s+""")
    private val tokenSplit = Regex("""[\s，,、/|]+""")

    /**
     * 从原始文案/标题提取降维后的召回关键词。
     * @param raw 分享文案、标题或用户输入
     * @return 降维关键词；无有效内容时返回 null
     */
    fun degrade(raw: String?): String? {
        if (raw.isNullOrBlank()) return null

        // 优先取「」【】中的标题主体；否则用去链接后的整段
        val base = bracketContent.find(raw)?.groupValues?.get(1)
            ?: urlRegex.replace(raw, " ")

        var cleaned = capacityRegex.replace(base, " ")
        // 去除括号残留与平台前缀噪音
        cleaned = cleaned.replace(Regex("""[「」【】\[\]（）()]"""), " ")

        val tokens = cleaned.split(tokenSplit)
            .map { it.trim() }
            .filter { token ->
                token.isNotBlank() &&
                    specWords.none { spec -> token.equals(spec, ignoreCase = true) } &&
                    !token.startsWith("http")
            }

        val keyword = tokens.joinToString(" ").let { multiSpace.replace(it, " ") }.trim()
        return keyword.ifBlank { null }
    }
}
