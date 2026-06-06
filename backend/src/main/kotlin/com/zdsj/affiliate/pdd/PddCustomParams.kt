package com.zdsj.affiliate.pdd

/**
 * 多多进宝 custom_parameters 构造：用业务用户 ID 生成稳定标识，
 * 用于比价预判与归因备案。pid + custom_parameters 唯一映射一个拼多多用户。
 */
object PddCustomParams {

    /** 由用户标识生成 custom_parameters（JSON：{"uid":"<uid>"}）；空则返回 null（游客不备案） */
    fun of(uid: String?): String? {
        val trimmed = uid?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        // uid 仅保留字母数字下划线，避免签名/解析异常
        val safe = trimmed.filter { it.isLetterOrDigit() || it == '_' }
        if (safe.isEmpty()) return null
        return """{"uid":"$safe"}"""
    }
}
