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
        val items = seedProps.enabledItems().filter { it.isNameMode() }
        if (items.isEmpty()) return
        log.info("种子采价[每日] 共 {} 个商品名", items.size)
        for (seed in items) {
            for (platformCode in seed.platforms) {
                runCatching { pollByName(seed, platformCode) }.onFailure {
                    log.warn("种子采价失败 name={} platform={}: {}", seed.name, platformCode, it.message)
                }
            }
        }
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
        priceService.recordSnapshot(raw.id!!, sku?.id, item.platform, priceResult)
        return priceResult
    }
}
