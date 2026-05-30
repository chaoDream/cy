package com.zdsj.user

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor

/**
 * 解析 Authorization: Bearer <token>，写入请求属性 userId。
 * 受保护路径在 WebConfig 中配置；公开路径放行（游客）。
 */
@Component
class AuthInterceptor(private val jwtService: JwtService) : HandlerInterceptor {

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        val header = request.getHeader("Authorization")
        if (header != null && header.startsWith("Bearer ")) {
            val userId = jwtService.verify(header.substring(7))
            if (userId != null) {
                request.setAttribute(USER_ID_ATTR, userId)
                return true
            }
        }
        response.status = HttpServletResponse.SC_UNAUTHORIZED
        response.contentType = "application/json;charset=UTF-8"
        response.writer.write("""{"code":1401,"message":"未登录或登录已过期","data":null}""")
        return false
    }

    companion object {
        const val USER_ID_ATTR = "zdsj.userId"
    }
}

/** 从请求中取出当前登录用户 id（受保护接口必有） */
fun HttpServletRequest.currentUserId(): Long =
    getAttribute(AuthInterceptor.USER_ID_ATTR) as? Long
        ?: throw com.zdsj.common.BizException(com.zdsj.common.ErrorCode.UNAUTHORIZED, "未登录")
