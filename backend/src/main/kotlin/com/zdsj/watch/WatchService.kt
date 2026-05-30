package com.zdsj.watch

import com.zdsj.common.BizException
import com.zdsj.common.ErrorCode
import com.zdsj.price.PriceService
import com.zdsj.product.ProductMappingRepository
import com.zdsj.product.ProductRawRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.OffsetDateTime

@Service
class WatchService(
    private val watchRepo: WatchItemRepository,
    private val rawRepo: ProductRawRepository,
    private val mappingRepo: ProductMappingRepository,
    private val priceService: PriceService,
) {

    /**
     * 创建盯价。targetPrice 为空时按默认目标价规则（PRD §5.5）计算：
     *  - 有 30 天低价 → 默认 = 近 30 天低价
     *  - 当前接近低价 → 当前价再降 3%
     *  - 历史数据不足 → 当前价再降 5%
     */
    @Transactional
    fun create(userId: Long, rawProductId: Long, skuId: Long?, targetPrice: BigDecimal?): WatchItem {
        val raw = rawRepo.findById(rawProductId)
            .orElseThrow { BizException(ErrorCode.NOT_FOUND, "商品不存在") }
        val resolvedSkuId = skuId ?: mappingRepo.findByRawProductId(rawProductId).map { it.skuId }.orElse(null)
        val currentPrice = raw.rawPrice ?: BigDecimal.ZERO

        val finalTarget = targetPrice ?: defaultTarget(resolvedSkuId, currentPrice)

        val existing = watchRepo.findByUserIdAndRawProductId(userId, rawProductId)
        val entity = existing.orElseGet { WatchItem(userId = userId, rawProductId = rawProductId) }
        entity.skuId = resolvedSkuId
        entity.targetPrice = finalTarget
        entity.currentPrice = currentPrice
        entity.notifyEnabled = true
        entity.status = "watching"
        entity.updatedAt = OffsetDateTime.now()
        return watchRepo.save(entity)
    }

    private fun defaultTarget(skuId: Long?, currentPrice: BigDecimal): BigDecimal {
        val low30 = skuId?.let { priceService.low30(it) }
        return when {
            low30 != null -> low30
            // 接近低价的判断需历史，无历史时默认降 5%
            else -> currentPrice.multiply(BigDecimal("0.95"))
        }.setScale(2, RoundingMode.HALF_UP)
    }

    fun listByUser(userId: Long): List<WatchItem> = watchRepo.findByUserIdOrderByCreatedAtDesc(userId)

    @Transactional
    fun updateTarget(userId: Long, watchId: Long, targetPrice: BigDecimal): WatchItem {
        val w = watchRepo.findById(watchId).orElseThrow { BizException(ErrorCode.NOT_FOUND, "盯价不存在") }
        if (w.userId != userId) throw BizException(ErrorCode.UNAUTHORIZED, "无权操作")
        w.targetPrice = targetPrice
        w.status = "watching"
        w.updatedAt = OffsetDateTime.now()
        return watchRepo.save(w)
    }

    @Transactional
    fun toggleNotify(userId: Long, watchId: Long, enabled: Boolean): WatchItem {
        val w = watchRepo.findById(watchId).orElseThrow { BizException(ErrorCode.NOT_FOUND, "盯价不存在") }
        if (w.userId != userId) throw BizException(ErrorCode.UNAUTHORIZED, "无权操作")
        w.notifyEnabled = enabled
        w.updatedAt = OffsetDateTime.now()
        return watchRepo.save(w)
    }

    @Transactional
    fun remove(userId: Long, watchId: Long) {
        val w = watchRepo.findById(watchId).orElseThrow { BizException(ErrorCode.NOT_FOUND, "盯价不存在") }
        if (w.userId != userId) throw BizException(ErrorCode.UNAUTHORIZED, "无权操作")
        watchRepo.delete(w)
    }
}
