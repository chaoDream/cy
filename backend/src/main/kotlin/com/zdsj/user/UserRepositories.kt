package com.zdsj.user

import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional

interface AppUserRepository : JpaRepository<AppUser, Long> {
    fun findByOpenid(openid: String): Optional<AppUser>
}

interface UserProfileRepository : JpaRepository<UserProfile, Long>
