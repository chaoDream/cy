package com.zdsj.watch

import com.zdsj.affiliate.JdGoodsMatcher
import com.zdsj.affiliate.Platform
import com.zdsj.affiliate.provider.AffiliateGateway
import com.zdsj.notify.NotifyService
import com.zdsj.price.PriceEngine
import com.zdsj.price.PriceService
import com.zdsj.price.UserAssets
import com.zdsj.product.ProductRaw
import com.zdsj.product.ProductRawRepository
import com.zdsj.product.ProductSkuRepository
import com.zdsj.user.AppUserRepository
import com.zdsj.user.UserService
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.OffsetDateTime

/**
 * 漏斗轮询盯价（PRD §5.5 / §9.4）。
 * 三档频率（cron 见 application.yml）控制联盟 API 调用成本与限频：
 *  - hot   : 热门 SKU 每 2h
 *  - user  : 用户关注 每 4h
 *  - normal: 普通每天
 * 命中目标价 → 记录快照 + 写 alert_hit_record + 下发订阅消息。
 */
@Component
class WatchPollingJob(
    private val watchRepo: WatchItemRepository,
    private val hitRepo: AlertHitRecordRepository,
    private val rawRepo: ProductRawRepository,
    private val skuRepo: ProductSkuRepository,
    private val userRepo: AppUserRepository,
    private val userService: UserService,
    private val gateway: AffiliateGateway,
    private val priceEngine: PriceEngine,
    private val priceService: PriceService,
    private val notifyService: NotifyService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${zdsj.watch.poll-hot-cron}")
    fun pollHot() = poll("hot")

    @Scheduled(cron = "\${zdsj.watch.poll-user-cron}")
    fun pollUser() = poll("user")

    @Scheduled(cron = "\${zdsj.watch.poll-normal-cron}")
    fun pollNormal() = poll("normal")

    @Transactional
    fun poll(tier: String) {
        val items = watchRepo.findByPollTierAndStatus(tier, "watching")
        if (items.isEmpty()) return
        log.info("漏斗轮询[{}] 共 {} 条", tier, items.size)
        for (w in items) {
            runCatching { pollOne(w) }.onFailure { log.warn("盯价轮询失败 watchId={}: {}", w.id, it.message) }
        }
    }

    private fun pollOne(w: WatchItem) {
        val raw = rawRepo.findById(w.rawProductId).orElse(null) ?: return
        val platform = Platform.fromCode(raw.platform) ?: return
        val user = userRepo.findById(w.userId).orElse(null) ?: return
        val assets = UserAssets.from(userService.getProfile(w.userId).assetsJson)

        // platform_lowest 取该平台同款全商家最低；搜索无果时回退为只盯当前商家，避免静默失效
        val quote = when (w.watchMode) {
            MODE_PLATFORM_LOWEST -> lowestOnPlatform(w, raw, platform, assets) ?: fetchMerchant(w, raw, platform, assets)
            else -> fetchMerchant(w, raw, platform, assets)
        } ?: return

        val current = quote.price
        w.currentPrice = current
        w.updatedAt = OffsetDateTime.now()

        if (w.notifyEnabled && current <= w.targetPrice) {
            val sent = notifyService.sendPriceDropAlert(
                openid = user.openid,
                productTitle = raw.title,
                currentPrice = current,
                targetPrice = w.targetPrice,
                page = "packageA/pages/analysis/analysis?platform=${platform.code}&itemId=${quote.itemId}",
            )
            if (sent) {
                hitRepo.save(AlertHitRecord(watchItemId = w.id!!, hitPrice = current))
                w.status = "hit"   // 一次订阅一次下发，命中后置 hit，待用户复订
            }
        }
        watchRepo.save(w)
    }

    /** 命中价 + 命中的平台商品 ID（决定通知跳转去哪条链接） */
    private data class Quote(val price: BigDecimal, val itemId: String)

    /** 只盯当前商家这条链接 */
    private fun fetchMerchant(w: WatchItem, raw: ProductRaw, platform: Platform, assets: UserAssets): Quote? {
        val result = gateway.fetchItem(platform, raw.platformItemId, bypassCache = true)
        val item = result.data ?: return null
        // 取价降级或拿到空价（rawPrice=0）时跳过，避免用脏数据把历史正确到手价覆盖成 0
        if (result.degraded || item.rawPrice <= BigDecimal.ZERO) {
            log.warn("盯价取价降级/空价，跳过更新 watchId={} platform={} item={}", w.id, platform.code, raw.platformItemId)
            return null
        }
        val priceResult = priceEngine.compute(item, assets)
        if (priceResult.estimatedFinalPrice <= BigDecimal.ZERO) return null
        priceService.recordSnapshot(raw.id!!, w.skuId, platform.code, priceResult)
        return Quote(priceResult.estimatedFinalPrice, raw.platformItemId)
    }

    /** 盯该平台同款所有商家：按 SKU 搜索，匹配过滤后取估算到手价最低的一条 */
    private fun lowestOnPlatform(w: WatchItem, raw: ProductRaw, platform: Platform, assets: UserAssets): Quote? {
        val sku = w.skuId?.let { skuRepo.findById(it).orElse(null) } ?: return null
        val keyword = "${sku.brand} ${sku.model}".trim()
        if (keyword.isBlank()) return null
        val hits = gateway.search(platform, keyword, limit = 20).data ?: return null
        // 用原商品标题过滤召回噪声 + 剔除 0 价脏数据，避免把降级空价误当同款最低
        val best = hits
            .filter { it.rawPrice > BigDecimal.ZERO && JdGoodsMatcher.matchesShareTitle(raw.title, it) }
            .map { it to priceEngine.compute(it, assets) }
            .filter { it.second.estimatedFinalPrice > BigDecimal.ZERO }
            .minByOrNull { it.second.estimatedFinalPrice }
            ?: return null
        priceService.recordSnapshot(raw.id!!, w.skuId, platform.code, best.second)
        return Quote(best.second.estimatedFinalPrice, best.first.platformItemId)
    }
}
