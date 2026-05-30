package com.zdsj.price

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.math.BigDecimal
import java.time.OffsetDateTime

interface PriceSnapshotRepository : JpaRepository<PriceSnapshot, Long> {

    fun findFirstByRawProductIdOrderByCapturedAtDesc(rawProductId: Long): PriceSnapshot?

    @Query(
        """
        SELECT p FROM PriceSnapshot p
        WHERE p.skuId = :skuId AND p.capturedAt >= :since
        ORDER BY p.capturedAt ASC
        """,
    )
    fun findBySkuSince(@Param("skuId") skuId: Long, @Param("since") since: OffsetDateTime): List<PriceSnapshot>

    @Query(
        """
        SELECT MIN(p.estimatedFinalPrice) FROM PriceSnapshot p
        WHERE p.skuId = :skuId AND p.capturedAt >= :since
        """,
    )
    fun findMinFinalPriceBySkuSince(@Param("skuId") skuId: Long, @Param("since") since: OffsetDateTime): BigDecimal?

    /** 用于低价榜：取最近若干快照，应用层按 SKU 聚合最优价 */
    fun findTop200ByOrderByCapturedAtDesc(): List<PriceSnapshot>
}
