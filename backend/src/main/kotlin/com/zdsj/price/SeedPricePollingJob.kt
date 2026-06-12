package com.zdsj.price

import com.zdsj.affiliate.Platform
import com.zdsj.affiliate.provider.AffiliateGateway
import com.zdsj.config.PriceSeedProperties
import com.zdsj.product.ProductIngestService
import com.zdsj.sku.SkuService
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * 冷启动种子采价：YAML 配商品名 → 自动在京东/拼多多搜索 → 每天定时采价写历史。
 */
@Component
@ConditionalOnProperty(prefix = "zdsj.price-seed", name = ["enabled"], havingValue = "true")
class SeedPricePollingJob(
    private val seedProps: PriceSeedProperties,
    private val resolver: SeedPriceResolver,
    private val ingestService: ProductIngestService,
    private val skuService: SkuService,
    private val priceEngine: PriceEngine,
    private val priceService: PriceService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostConstruct
    fun logStartup() {
        val items = seedProps.enabledItems().filter { it.isNameMode() }
        log.info("种子采价已启用：共 {} 个商品名，cron={}", items.size, seedProps.pollCron)
    }

    /** 每天定时（默认 06:00） */
    @Scheduled(cron = "\${zdsj.price-seed.poll-cron}")
    fun pollDaily() {
        if (seedProps.enabledItems().filter { it.isNameMode() }.isEmpty()) return
        val r = runOnce("每日")
        log.info("种子采价[每日] 完成 共{} 成功{} 失败{} 耗时{}ms", r.total, r.success, r.failed, r.durationMs)
    }

    /**
     * 立即执行一次采价（供后台手动触发 / 补采）。返回成功失败统计，不抛异常。
     */
    fun runOnce(trigger: String = "手动"): SeedPollResult {
        val started = System.currentTimeMillis()
        val items = seedProps.enabledItems().filter { it.isNameMode() }
        log.info("种子采价[{}] 开始 共 {} 个商品名", trigger, items.size)
        var success = 0
        var failed = 0
        val failures = mutableListOf<String>()
        for (seed in items) {
            for (platformCode in seed.platforms) {
                runCatching { pollByName(seed, platformCode) }
                    .onSuccess { success++ }
                    .onFailure {
                        failed++
                        val msg = "${seed.name}/$platformCode: ${it.message}"
                        if (failures.size < 50) failures.add(msg)
                        log.warn("种子采价失败 {}", msg)
                    }
            }
        }
        return SeedPollResult(
            total = success + failed,
            success = success,
            failed = failed,
            durationMs = System.currentTimeMillis() - started,
            failures = failures,
        )
    }

    @Transactional
    fun pollByName(seed: PriceSeedProperties.SeedItem, platformCode: String) {
        val platform = Platform.fromCode(platformCode)
            ?: throw IllegalArgumentException("不支持的平台: $platformCode")
        val item = resolver.resolve(seed.name, platform, seedProps.searchLimit)
        val priceResult = recordSnapshot(item)
        resolver.touchBinding(seed.name, platform.code, ingestService.upsert(item).id!!)
        log.info(
            "种子采价成功 name={} platform={} itemId={} title={} finalPrice={}",
            seed.name, platform.code, item.platformItemId, item.title.take(40),
            priceResult.estimatedFinalPrice,
        )
    }

    private fun recordSnapshot(item: com.zdsj.affiliate.AffiliateItem): FinalPriceResult {
        val raw = ingestService.upsert(item)
        val (sku, _) = skuService.resolveAndPersist(raw.id!!, item)
        val priceResult = priceEngine.compute(item, UserAssets())
        if (!priceResult.pricePending) {
            priceService.recordSnapshot(raw.id!!, sku?.id, item.platform, priceResult)
        }
        return priceResult
    }
}

/** 一次采价的结果汇总（手动触发接口返回用）。 */
data class SeedPollResult(
    val total: Int,
    val success: Int,
    val failed: Int,
    val durationMs: Long,
    val failures: List<String>,
)
