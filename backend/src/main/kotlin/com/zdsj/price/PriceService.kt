package com.zdsj.price

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.OffsetDateTime

data class TrendPoint(val date: String, val price: BigDecimal)

data class PriceTrend(
    val points: List<TrendPoint>,
    val low30: BigDecimal?,
    val low90: BigDecimal?,
    val nearLow: Boolean,             // 当前是否接近近 30 天低价
    val fakeDiscount: Boolean,        // 先涨后降
    val historyInsufficient: Boolean,
    val note: String?,
)

@Service
class PriceService(private val snapshotRepo: PriceSnapshotRepository) {

    /** 加入盯价/查询即记录快照（PRD §9.4 第一天就攒） */
    @Transactional
    fun recordSnapshot(
        rawProductId: Long,
        skuId: Long?,
        platform: String,
        result: FinalPriceResult,
    ): PriceSnapshot = snapshotRepo.save(
        PriceSnapshot(
            rawProductId = rawProductId,
            skuId = skuId,
            platform = platform,
            displayPrice = result.displayPrice,
            estimatedFinalPrice = result.estimatedFinalPrice,
            couponAmount = result.couponAmount,
            subsidyAmount = result.subsidyAmount,
            freight = result.freight,
            uncertaintyFlags = result.uncertaintyFlags.toMutableList(),
        ),
    )

    fun low30(skuId: Long): BigDecimal? =
        snapshotRepo.findMinFinalPriceBySkuSince(skuId, OffsetDateTime.now().minusDays(30))

    fun low90(skuId: Long): BigDecimal? =
        snapshotRepo.findMinFinalPriceBySkuSince(skuId, OffsetDateTime.now().minusDays(90))

    /** 30/90 天趋势 + 反套路（先涨后降）识别（PRD §5.6） */
    fun trend(skuId: Long?, currentPrice: BigDecimal): PriceTrend {
        if (skuId == null) {
            return PriceTrend(emptyList(), null, null, false, false, true, "历史价格积累中")
        }
        val since = OffsetDateTime.now().minusDays(90)
        val snapshots = snapshotRepo.findBySkuSince(skuId, since)
        if (snapshots.size < 3) {
            return PriceTrend(
                points = snapshots.map { TrendPoint(it.capturedAt.toLocalDate().toString(), it.estimatedFinalPrice) },
                low30 = low30(skuId), low90 = low90(skuId),
                nearLow = false, fakeDiscount = false,
                historyInsufficient = true, note = "历史价格积累中",
            )
        }
        val points = snapshots.map { TrendPoint(it.capturedAt.toLocalDate().toString(), it.estimatedFinalPrice) }
        val l30 = low30(skuId)
        val l90 = low90(skuId)
        val nearLow = l30 != null && currentPrice <= l30 * BigDecimal("1.03")

        // 先涨后降：近 30 天常态价（中位数）显著低于当前"促销价"对照的标价
        val recent30 = snapshots.filter { it.capturedAt >= OffsetDateTime.now().minusDays(30) }
        val medianNormal = recent30.map { it.displayPrice }.sorted().let {
            if (it.isEmpty()) BigDecimal.ZERO else it[it.size / 2]
        }
        val currentDisplay = recent30.lastOrNull()?.displayPrice ?: currentPrice
        val fakeDiscount = medianNormal > BigDecimal.ZERO &&
            currentDisplay > medianNormal * BigDecimal("1.05")

        return PriceTrend(
            points = points, low30 = l30, low90 = l90,
            nearLow = nearLow, fakeDiscount = fakeDiscount,
            historyInsufficient = false,
            note = if (fakeDiscount) "⚠️ 折扣可能不实（先涨后降）" else null,
        )
    }
}
