package com.zdsj.user

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.OffsetDateTime

@Entity
@Table(name = "app_user")
class AppUser(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(nullable = false, unique = true)
    var openid: String = "",

    var nickname: String? = null,

    @Column(name = "created_at")
    var createdAt: OffsetDateTime = OffsetDateTime.now(),
)

@Entity
@Table(name = "user_profile")
class UserProfile(
    @Id
    @Column(name = "user_id")
    var userId: Long = 0,

    /** 88VIP/PLUS/省钱月卡/国补省市 自申报资产 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "assets_json", columnDefinition = "jsonb")
    var assetsJson: MutableMap<String, Any?> = mutableMapOf(),

    var region: String? = null,

    @Column(name = "updated_at")
    var updatedAt: OffsetDateTime = OffsetDateTime.now(),
)
