package com.zdsj.common

/**
 * 统一响应包装。小程序请求层按 code==0 判定成功。
 */
data class ApiResponse<T>(
    val code: Int,
    val message: String,
    val data: T?,
) {
    companion object {
        fun <T> ok(data: T?): ApiResponse<T> = ApiResponse(0, "ok", data)
        fun <T> fail(code: Int, message: String): ApiResponse<T> = ApiResponse(code, message, null)
    }
}
