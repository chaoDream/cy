package com.zdsj.watch

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.OffsetDateTime

@Entity
@Table(name = "watch_item")
class WatchItem(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null,
    @Column(name = "user_id") var userId: Long = 0,
    @Column(name = "sku_id") var skuId: Long? = null,
    @Column(name = "raw_product_id") var rawProductId: Long = 0,
    @Column(name = "target_price") var targetPrice: BigDecimal = BigDecimal.ZERO,
    @Column(name = "current_price") var currentPrice: BigDecimal? = null,
    @Column(name = "notify_enabled") var notifyEnabled: Boolean = true,
    @Column(name = "poll_tier") var pollTier: String = "user",
    // merchant=只盯当前商家这条链接；platform_lowest=盯该平台同款所有商家的最低价
    @Column(name = "watch_mode") var watchMode: String = "merchant",
    var status: String = "watching",
    @Column(name = "created_at") var createdAt: OffsetDateTime = OffsetDateTime.now(),
    @Column(name = "updated_at") var updatedAt: OffsetDateTime = OffsetDateTime.now(),
)

@Entity
@Table(name = "alert_hit_record")
class AlertHitRecord(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null,
    @Column(name = "watch_item_id") var watchItemId: Long = 0,
    @Column(name = "hit_price") var hitPrice: BigDecimal = BigDecimal.ZERO,
    @Column(name = "notified_at") var notifiedAt: OffsetDateTime = OffsetDateTime.now(),
)
