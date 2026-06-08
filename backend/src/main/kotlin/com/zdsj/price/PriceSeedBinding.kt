package com.zdsj.price

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.OffsetDateTime

@Entity
@Table(name = "price_seed_binding")
class PriceSeedBinding(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null,
    @Column(name = "seed_name") var seedName: String = "",
    var platform: String = "",
    @Column(name = "platform_item_id") var platformItemId: String = "",
    @Column(name = "raw_product_id") var rawProductId: Long? = null,
    @Column(name = "resolved_title") var resolvedTitle: String? = null,
    @Column(name = "last_polled_at") var lastPolledAt: OffsetDateTime? = null,
    @Column(name = "created_at") var createdAt: OffsetDateTime = OffsetDateTime.now(),
    @Column(name = "updated_at") var updatedAt: OffsetDateTime = OffsetDateTime.now(),
)
