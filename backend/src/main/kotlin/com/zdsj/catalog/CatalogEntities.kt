package com.zdsj.catalog

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
@Table(name = "catalog_brand")
class CatalogBrand(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null,
    @Column(name = "canonical_name") var canonicalName: String = "",
    @JdbcTypeCode(SqlTypes.JSON) var aliases: MutableList<String> = mutableListOf(),
    @Column(name = "source_platform") var sourcePlatform: String = "system",
    @Column(name = "sample_count") var sampleCount: Int = 0,
    @Column(name = "last_seen_at") var lastSeenAt: OffsetDateTime? = null,
    var status: String = "active",
    @Column(name = "created_at") var createdAt: OffsetDateTime = OffsetDateTime.now(),
    @Column(name = "updated_at") var updatedAt: OffsetDateTime = OffsetDateTime.now(),
)

@Entity
@Table(name = "catalog_model")
class CatalogModel(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null,
    var brand: String = "",
    var model: String = "",
    @JdbcTypeCode(SqlTypes.JSON) var aliases: MutableList<String> = mutableListOf(),
    @Column(name = "source_platform") var sourcePlatform: String = "system",
    @Column(name = "example_title") var exampleTitle: String? = null,
    @Column(name = "sample_count") var sampleCount: Int = 0,
    @Column(name = "last_seen_at") var lastSeenAt: OffsetDateTime? = null,
    var status: String = "active",
    @Column(name = "created_at") var createdAt: OffsetDateTime = OffsetDateTime.now(),
    @Column(name = "updated_at") var updatedAt: OffsetDateTime = OffsetDateTime.now(),
)
