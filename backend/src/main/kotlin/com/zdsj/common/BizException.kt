package com.zdsj.common

/**
 * 业务异常，携带错误码。错误码与 ErrorCode 对齐。
 */
class BizException(
    val code: Int,
    override val message: String,
) : RuntimeException(message)

object ErrorCode {
    const val PARAM_INVALID = 1001
    const val UNAUTHORIZED = 1401
    const val NOT_FOUND = 1404

    // 链接解析
    const val LINK_EMPTY = 2001
    const val PLATFORM_UNSUPPORTED = 2002
    const val NOT_PHONE_CATEGORY = 2003
    const val PARSE_FAILED = 2004

    // 联盟 / 外部
    const val AFFILIATE_RATE_LIMIT = 3001
    const val AFFILIATE_ERROR = 3002

    // AI
    const val AI_ERROR = 4001
}
