package com.zdsj.user

import com.zdsj.common.ApiResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.constraints.NotBlank
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class LoginRequest(@field:NotBlank val code: String = "", val nickname: String? = null)

/** 省钱资产库（PRD §5.1） */
data class AssetsRequest(
    val vip88: Boolean = false,
    val jdPlus: Boolean = false,
    val pddMonthly: Boolean = false,
    val govSubsidyRegion: String? = null,
)

@RestController
@RequestMapping("/api/user")
class UserController(private val userService: UserService) {

    @PostMapping("/login")
    fun login(@RequestBody req: LoginRequest): ApiResponse<LoginResult> =
        ApiResponse.ok(userService.loginByCode(req.code, req.nickname))

    @GetMapping("/profile")
    fun profile(request: HttpServletRequest): ApiResponse<Map<String, Any?>> {
        val p = userService.getProfile(request.currentUserId())
        return ApiResponse.ok(mapOf("assets" to p.assetsJson, "region" to p.region))
    }

    @PostMapping("/assets")
    fun updateAssets(request: HttpServletRequest, @RequestBody req: AssetsRequest): ApiResponse<Map<String, Any?>> {
        val assets = mutableMapOf<String, Any?>(
            "vip88" to req.vip88,
            "jdPlus" to req.jdPlus,
            "pddMonthly" to req.pddMonthly,
            "govSubsidyRegion" to req.govSubsidyRegion,
        )
        val p = userService.updateAssets(request.currentUserId(), assets, req.govSubsidyRegion)
        return ApiResponse.ok(mapOf("assets" to p.assetsJson, "region" to p.region))
    }
}
