package com.zdsj.common

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(BizException::class)
    fun handleBiz(e: BizException): ApiResponse<Nothing> {
        return ApiResponse.fail(e.code, e.message)
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleValidation(e: MethodArgumentNotValidException): ApiResponse<Nothing> {
        val msg = e.bindingResult.fieldErrors.joinToString(";") { "${it.field}: ${it.defaultMessage}" }
        return ApiResponse.fail(ErrorCode.PARAM_INVALID, msg.ifBlank { "参数错误" })
    }

    @ExceptionHandler(Exception::class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    fun handleOther(e: Exception): ApiResponse<Nothing> {
        log.error("未处理异常", e)
        return ApiResponse.fail(500, "服务器开小差了，请稍后重试")
    }
}
