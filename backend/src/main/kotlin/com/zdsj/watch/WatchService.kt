package com.zdsj.watch

import com.zdsj.common.BizException
import com.zdsj.common.ErrorCode
import com.zdsj.price.PriceEngine
import com.zdsj.price.PriceService
import com.zdsj.price.UserAssets
import com.zdsj.product.ProductImageStorageService
import com.zdsj.product.ProductIngestService
import com.zdsj.product.ProductMappingRepository
import com.zdsj.product.ProductRaw
import com.zdsj.product.ProductRawRepository
import com.zdsj.user.AppUserRepository
import com.zdsj.user.UserService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.OffsetDateTime

/** 只盯当前商家这条链接 */
const val MODE_MERCHANT = "merchant"
/** 盯该平台同款所有商家的最低价 */
const val MODE_PLATFORM_LOWEST = "platform_lowest"

@Service
class WatchService(
    private val watchRepo: WatchItemRepository,
    private val rawRepo: ProductRawRepository,
    private val mappingRepo: ProductMappingRepository,
    private val priceService: PriceService,
    private val priceEngine: PriceEngine,
    private val ingestService: ProductIngestService,
    private val imageStorage: ProductImageStorageService,
    private val userService: UserService,
    private val userRepo: AppUserRepository,
) {

    /**
     * 创建盯价。targetPrice 为空时按默认目标价规则（PRD §5.5）计算：
     *  - 有 30 天低价 → 默认 = 近 30 天低价
     *  - 当前接近低价 → 当前价再降 3%
     *  - 历史数据不足 → 当前价再降 5%
     */
    @Transactional
    fun create(
        userId: Long,
        rawProductId: Long,
        skuId: Long?,
        targetPrice: BigDecimal?,
        watchMode: String = "merchant",
    ): WatchItem {
        // token 中 userId 可能因数据库重置而失效，提前返回业务错误，避免 DB 外键异常 500
        if (!userRepo.existsById(userId)) {
            throw BizException(ErrorCode.UNAUTHORIZED, "登录态已失效，请重新进入小程序登录后再试")
        }
        val raw = rawRepo.findById(rawProductId)
            .orElseThrow { BizException(ErrorCode.NOT_FOUND, "商品不存在") }
        raw.id?.let { imageStorage.persistIfAbsentAsync(it) }
        val resolvedSkuId = skuId ?: mappingRepo.findByRawProductId(rawProductId).map { it.skuId }.orElse(null)
        // 当前价存「到手价」而非挂牌价，与列表「当前到手价」语义一致；算不出时回退挂牌价
        val currentPrice = estimateFinalPrice(userId, raw)

        // platform_lowest 需按 SKU 全平台搜索；无 SKU 时无召回词，自动回退为只盯当前商家
        val resolvedMode = normalizeMode(watchMode).takeUnless { it == MODE_PLATFORM_LOWEST && resolvedSkuId == null }
            ?: MODE_MERCHANT

        val finalTarget = targetPrice ?: defaultTarget(resolvedSkuId, currentPrice)

        val existing = watchRepo.findByUserIdAndRawProductId(userId, rawProductId)
        val entity = existing.orElseGet { WatchItem(userId = userId, rawProductId = rawProductId) }
        entity.skuId = resolvedSkuId
        entity.targetPrice = finalTarget
        entity.currentPrice = currentPrice
        entity.notifyEnabled = true
        entity.watchMode = resolvedMode
        entity.status = "watching"
        entity.updatedAt = OffsetDateTime.now()
        return watchRepo.save(entity)
    }

    @Transactional
    fun updateMode(userId: Long, watchId: Long, watchMode: String): WatchItem {
        val w = watchRepo.findById(watchId).orElseThrow { BizException(ErrorCode.NOT_FOUND, "盯价不存在") }
        if (w.userId != userId) throw BizException(ErrorCode.UNAUTHORIZED, "无权操作")
        val mode = normalizeMode(watchMode)
        if (mode == MODE_PLATFORM_LOWEST && w.skuId == null) {
            throw BizException(ErrorCode.PARAM_INVALID, "该商品暂未识别型号，无法盯全平台同款最低价")
        }
        w.watchMode = mode
        w.updatedAt = OffsetDateTime.now()
        return watchRepo.save(w)
    }

    private fun normalizeMode(mode: String?): String =
        if (mode == MODE_PLATFORM_LOWEST) MODE_PLATFORM_LOWEST else MODE_MERCHANT

    /** 按用户资产算当前到手价；挂牌价缺失或算出非正数时回退挂牌价 */
    private fun estimateFinalPrice(userId: Long, raw: ProductRaw): BigDecimal {
        val original = raw.rawPrice ?: BigDecimal.ZERO
        if (original <= BigDecimal.ZERO) return original
        val assets = UserAssets.from(userService.getProfile(userId).assetsJson)
        val price = priceEngine.compute(ingestService.toAffiliateItem(raw), assets).estimatedFinalPrice
        return if (price > BigDecimal.ZERO) price else original
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

    /** 全局开关：一次性设置该用户所有盯价的提醒开关，返回受影响条数 */
    @Transactional
    fun setNotifyAll(userId: Long, enabled: Boolean): Int {
        val items = watchRepo.findByUserIdOrderByCreatedAtDesc(userId)
        val now = OffsetDateTime.now()
        items.forEach {
            it.notifyEnabled = enabled
            it.updatedAt = now
        }
        watchRepo.saveAll(items)
        return items.size
    }

    @Transactional
    fun remove(userId: Long, watchId: Long) {
        val w = watchRepo.findById(watchId).orElseThrow { BizException(ErrorCode.NOT_FOUND, "盯价不存在") }
        if (w.userId != userId) throw BizException(ErrorCode.UNAUTHORIZED, "无权操作")
        watchRepo.delete(w)
    }
}
