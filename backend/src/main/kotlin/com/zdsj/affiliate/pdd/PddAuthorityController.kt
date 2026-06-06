package com.zdsj.affiliate.pdd

import com.zdsj.common.ApiResponse
import com.zdsj.user.currentUserId
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 多多进宝用户备案：将当前登录用户与 pid + custom_parameters 绑定，
 * 是「比价预判 / 归因」生效的前置。前端拿到授权跳转信息后引导用户在拼多多完成授权。
 *
 * 受保护路径（需登录），custom_parameters 由 currentUserId 派生，确保与分析页传入的 uid 一致。
 */
@RestController
@RequestMapping("/api/pdd/authority")
class PddAuthorityController(private val pddDdkService: PddDdkService) {

    /** 查询当前用户是否已备案 */
    @GetMapping("/status")
    fun status(request: HttpServletRequest): ApiResponse<Map<String, Any?>> {
        val cp = customParams(request)
        val authorized = pddDdkService.isAuthorized(cp)
        return ApiResponse.ok(mapOf("authorized" to authorized, "customParameters" to cp))
    }

    /** 发起备案：返回拼多多小程序授权跳转信息（app_id / page_path 等） */
    @PostMapping("/bind")
    fun bind(request: HttpServletRequest): ApiResponse<Map<String, Any?>> {
        val cp = customParams(request)
        val result = pddDdkService.bindAuthority(cp)
        return ApiResponse.ok(
            mapOf(
                "customParameters" to cp,
                "jump" to result,
            ),
        )
    }

    private fun customParams(request: HttpServletRequest): String =
        PddCustomParams.of(request.currentUserId().toString())
            ?: error("无法生成 custom_parameters")
}
