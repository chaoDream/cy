package com.zdsj.user

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

data class LoginResult(val token: String, val userId: Long, val nickname: String?, val avatarUrl: String?)

@Service
class UserService(
    private val userRepo: AppUserRepository,
    private val profileRepo: UserProfileRepository,
    private val wechatClient: WechatClient,
    private val jwtService: JwtService,
    private val avatarStorage: AvatarStorageService,
) {

    @Transactional
    fun loginByCode(code: String, nickname: String?, avatarUrl: String?): LoginResult {
        val openid = wechatClient.code2Session(code).openid
        val user = userRepo.findByOpenid(openid).orElseGet {
            val created = userRepo.save(AppUser(openid = openid, nickname = nickname ?: randomNickname()))
            profileRepo.save(UserProfile(userId = created.id!!))
            created
        }
        var changed = false
        // 老用户无昵称时补一次随机昵称并持久化，避免每次登录昵称变化
        if (user.nickname.isNullOrBlank()) {
            user.nickname = randomNickname()
            changed = true
        }
        if (nickname != null && nickname.isNotBlank() && nickname != user.nickname) {
            user.nickname = nickname
            changed = true
        }
        val persistedAvatar = avatarStorage.persist(user.id!!, avatarUrl)
        if (persistedAvatar != null && persistedAvatar != user.avatarUrl) {
            user.avatarUrl = persistedAvatar
            changed = true
        }
        if (changed) userRepo.save(user)
        val token = jwtService.issue(user.id!!, openid)
        return LoginResult(token, user.id!!, user.nickname, user.avatarUrl)
    }

    private fun randomNickname(): String = "省心用户${(1000..9999).random()}"

    fun getUser(userId: Long): AppUser =
        userRepo.findById(userId).orElseThrow { com.zdsj.common.BizException(com.zdsj.common.ErrorCode.NOT_FOUND, "用户不存在") }

    @Transactional
    fun updateAvatar(userId: Long, avatarPath: String): AppUser {
        val user = getUser(userId)
        user.avatarUrl = avatarPath
        return userRepo.save(user)
    }

    fun getProfile(userId: Long): UserProfile =
        profileRepo.findById(userId).orElseGet { profileRepo.save(UserProfile(userId = userId)) }

    /** 省钱资产库勾选保存 */
    @Transactional
    fun updateAssets(userId: Long, assets: MutableMap<String, Any?>, region: String?): UserProfile {
        val profile = getProfile(userId)
        profile.assetsJson = assets
        if (region != null) profile.region = region
        profile.updatedAt = OffsetDateTime.now()
        return profileRepo.save(profile)
    }
}
