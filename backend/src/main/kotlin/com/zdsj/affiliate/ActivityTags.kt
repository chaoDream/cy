package com.zdsj.affiliate

/**
 * 活动标签清洗：过滤数据源占位标签，仅保留对用户有意义的营销/活动信息。
 */
object ActivityTags {

    /** 联盟通道降级或映射兜底时写入的内部标记，不应展示给用户 */
    private val INTERNAL = setOf(
        "京东联盟",
        "维易",
        "维易·京东联盟",
        "多多进宝",
    )

    fun sanitize(tags: List<String>): List<String> =
        tags.map { it.trim() }.filter { it.isNotBlank() && it !in INTERNAL }
}
