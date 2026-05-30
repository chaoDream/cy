package com.zdsj.price

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
@Table(name = "price_snapshot")
class PriceSnapshot(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null,
    @Column(name = "raw_product_id") var rawProductId: Long = 0,
    @Column(name = "sku_id") var skuId: Long? = null,
    var platform: String = "",
    @Column(name = "display_price") var displayPrice: BigDecimal = BigDecimal.ZERO,
    @Column(name = "estimated_final_price") var estimatedFinalPrice: BigDecimal = BigDecimal.ZERO,
    @Column(name = "coupon_amount") var couponAmount: BigDecimal = BigDecimal.ZERO,
    @Column(name = "subsidy_amount") var subsidyAmount: BigDecimal = BigDecimal.ZERO,
    var freight: BigDecimal = BigDecimal.ZERO,
    @Column(name = "rebate_estimate") var rebateEstimate: BigDecimal = BigDecimal.ZERO,
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "uncertainty_flags", columnDefinition = "jsonb")
    var uncertaintyFlags: MutableList<String> = mutableListOf(),
    @Column(name = "captured_at") var capturedAt: OffsetDateTime = OffsetDateTime.now(),
)
