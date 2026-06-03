package com.zdsj.user

import com.zdsj.common.ApiResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.constraints.NotBlank
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

data class LoginRequest(
    @field:NotBlank val code: String = "",
    val nickname: String? = null,
    val avatarUrl: String? = null,
)

/** 省钱资产库（PRD §5.1） */
data class AssetsRequest(
    val vip88: Boolean = false,
    val jdPlus: Boolean = false,
    val pddMonthly: Boolean = false,
    val govSubsidyRegion: String? = null,
)

@RestController
@RequestMapping("/api/user")
class UserController(
    private val userService: UserService,
    private val avatarStorage: AvatarStorageService,
) {

    @PostMapping("/login")
    fun login(@RequestBody req: LoginRequest): ApiResponse<LoginResult> =
        ApiResponse.ok(userService.loginByCode(req.code, req.nickname, req.avatarUrl))

    @GetMapping("/profile")
    fun profile(request: HttpServletRequest): ApiResponse<Map<String, Any?>> {
        val userId = request.currentUserId()
        val user = userService.getUser(userId)
        val p = userService.getProfile(userId)
        return ApiResponse.ok(
            mapOf(
                "nickname" to user.nickname,
                "avatarUrl" to user.avatarUrl,
                "assets" to p.assetsJson,
                "region" to p.region,
            ),
        )
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

    /** 小程序 chooseAvatar 上传头像（需已登录） */
    @PostMapping("/avatar", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun uploadAvatar(
        request: HttpServletRequest,
        @RequestParam("file") file: MultipartFile,
    ): ApiResponse<Map<String, String?>> {
        val userId = request.currentUserId()
        val bytes = file.bytes
        val path = avatarStorage.persistBytes(userId, bytes)
            ?: throw com.zdsj.common.BizException(com.zdsj.common.ErrorCode.PARAM_INVALID, "头像保存失败")
        val user = userService.updateAvatar(userId, path)
        return ApiResponse.ok(mapOf("avatarUrl" to user.avatarUrl))
    }
}
