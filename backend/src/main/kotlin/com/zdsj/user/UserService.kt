package com.zdsj.user

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

data class LoginResult(val token: String, val userId: Long, val nickname: String?)

@Service
class UserService(
    private val userRepo: AppUserRepository,
    private val profileRepo: UserProfileRepository,
    private val wechatClient: WechatClient,
    private val jwtService: JwtService,
) {

    @Transactional
    fun loginByCode(code: String, nickname: String?): LoginResult {
        val openid = wechatClient.code2Session(code).openid
        val user = userRepo.findByOpenid(openid).orElseGet {
            val created = userRepo.save(AppUser(openid = openid, nickname = nickname))
            profileRepo.save(UserProfile(userId = created.id!!))
            created
        }
        if (nickname != null && nickname != user.nickname) {
            user.nickname = nickname
            userRepo.save(user)
        }
        val token = jwtService.issue(user.id!!, openid)
        return LoginResult(token, user.id!!, user.nickname)
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
