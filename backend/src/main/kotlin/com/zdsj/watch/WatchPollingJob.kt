package com.zdsj.watch

import com.zdsj.affiliate.AffiliateRegistry
import com.zdsj.affiliate.Platform
import com.zdsj.notify.NotifyService
import com.zdsj.price.PriceEngine
import com.zdsj.price.PriceService
import com.zdsj.price.UserAssets
import com.zdsj.product.ProductRawRepository
import com.zdsj.user.AppUserRepository
import com.zdsj.user.UserService
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
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
    private val userRepo: AppUserRepository,
    private val userService: UserService,
    private val registry: AffiliateRegistry,
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
        val item = registry.get(platform).fetchItem(raw.platformItemId) ?: return

        val user = userRepo.findById(w.userId).orElse(null) ?: return
        val assets = UserAssets.from(userService.getProfile(w.userId).assetsJson)

        val priceResult = priceEngine.compute(item, assets)
        priceService.recordSnapshot(raw.id!!, w.skuId, platform.code, priceResult)

        val current = priceResult.estimatedFinalPrice
        w.currentPrice = current
        w.updatedAt = OffsetDateTime.now()

        if (w.notifyEnabled && current <= w.targetPrice) {
            val sent = notifyService.sendPriceDropAlert(
                openid = user.openid,
                productTitle = raw.title,
                currentPrice = current,
                targetPrice = w.targetPrice,
                page = "packageA/pages/analysis/analysis?platform=${raw.platform}&itemId=${raw.platformItemId}",
            )
            if (sent) {
                hitRepo.save(AlertHitRecord(watchItemId = w.id!!, hitPrice = current))
                w.status = "hit"   // 一次订阅一次下发，命中后置 hit，待用户复订
            }
        }
        watchRepo.save(w)
    }
}
