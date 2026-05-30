package com.zdsj.ai

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
@Table(name = "ai_analysis_record")
class AiAnalysisRecord(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null,
    @Column(name = "sku_id") var skuId: Long? = null,
    var platform: String? = null,
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "input_json", columnDefinition = "jsonb")
    var inputJson: MutableMap<String, Any?> = mutableMapOf(),
    var conclusion: String = "",
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "reasons", columnDefinition = "jsonb")
    var reasons: MutableList<String> = mutableListOf(),
    var confidence: String? = null,
    @Column(name = "created_at") var createdAt: OffsetDateTime = OffsetDateTime.now(),
)
