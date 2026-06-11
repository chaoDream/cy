package com.zdsj.product

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.math.BigDecimal
import java.time.OffsetDateTime

@Entity
@Table(name = "product_spu")
class ProductSpu(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null,
    var brand: String = "",
    var series: String? = null,
    var model: String = "",
    @Column(name = "official_price") var officialPrice: BigDecimal? = null,
    var status: String = "active",
    @Column(name = "created_at") var createdAt: OffsetDateTime = OffsetDateTime.now(),
    @Column(name = "updated_at") var updatedAt: OffsetDateTime = OffsetDateTime.now(),
)

@Entity
@Table(name = "product_raw")
class ProductRaw(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null,
    var platform: String = "",
    @Column(name = "platform_item_id") var platformItemId: String = "",
    var title: String = "",
    @Column(name = "image_url") var imageUrl: String? = null,
    /** 本地落盘后的访问路径，如 /static/products/jd/1000123.jpg */
    @Column(name = "local_image_path") var localImagePath: String? = null,
    @Column(name = "image_stored_at") var imageStoredAt: OffsetDateTime? = null,
    @Column(name = "shop_name") var shopName: String? = null,
    @Column(name = "shop_type") var shopType: String? = null,
    @Column(name = "raw_price") var rawPrice: BigDecimal? = null,
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "coupon_info", columnDefinition = "jsonb")
    var couponInfo: MutableMap<String, Any?> = mutableMapOf(),
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "activity_tags", columnDefinition = "jsonb")
    var activityTags: MutableList<String> = mutableListOf(),
    @Column(name = "source_url") var sourceUrl: String? = null,
    @Column(name = "created_at") var createdAt: OffsetDateTime = OffsetDateTime.now(),
    @Column(name = "updated_at") var updatedAt: OffsetDateTime = OffsetDateTime.now(),
)

@Entity
@Table(name = "product_sku")
class ProductSku(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null,
    var brand: String = "",
    var series: String? = null,
    var model: String = "",
    var storage: String? = null,
    var color: String? = null,
    @Column(name = "network_version") var networkVersion: String? = null,
    @Column(name = "region_version") var regionVersion: String? = null,
    var condition: String = "全新",
    @Column(name = "package_type") var packageType: String = "裸机",
    @Column(name = "standard_name") var standardName: String = "",
    @Column(name = "created_at") var createdAt: OffsetDateTime = OffsetDateTime.now(),
    @Column(name = "updated_at") var updatedAt: OffsetDateTime = OffsetDateTime.now(),
)

@Entity
@Table(name = "product_mapping")
class ProductMapping(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null,
    @Column(name = "raw_product_id") var rawProductId: Long = 0,
    @Column(name = "sku_id") var skuId: Long? = null,
    var confidence: String = "low",
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "risk_tags", columnDefinition = "jsonb")
    var riskTags: MutableList<String> = mutableListOf(),
    @Column(name = "ai_reason") var aiReason: String? = null,
    @Column(name = "review_status") var reviewStatus: String = "pending",
    @Column(name = "created_at") var createdAt: OffsetDateTime = OffsetDateTime.now(),
    @Column(name = "updated_at") var updatedAt: OffsetDateTime = OffsetDateTime.now(),
)

@Entity
@Table(name = "promotion_rule")
class PromotionRule(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null,
    var platform: String = "",
    @Column(name = "rule_type") var ruleType: String = "",
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "rule_json", columnDefinition = "jsonb")
    var ruleJson: MutableMap<String, Any?> = mutableMapOf(),
    @Column(name = "valid_from") var validFrom: OffsetDateTime? = null,
    @Column(name = "valid_to") var validTo: OffsetDateTime? = null,
)
