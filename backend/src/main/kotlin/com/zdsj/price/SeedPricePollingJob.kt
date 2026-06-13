package com.zdsj.price

import com.zdsj.affiliate.AffiliateItem
import com.zdsj.affiliate.JdSearchRemedy
import com.zdsj.affiliate.PddLinkParser
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
 * 冷启动种子采价：
 * 1. 已绑定数字 SKU / goods_sign → **批量直查**（京东 promotiongoodsinfo / 拼多多 goodssearch）
 * 2. 未绑定 → 名称搜索发现，成功后写入 price_seed_binding，下次走批量直查
 */
@Component
@ConditionalOnProperty(prefix = "zdsj.price-seed", name = ["enabled"], havingValue = "true")
class SeedPricePollingJob(
    private val seedProps: PriceSeedProperties,
    private val seedComposer: DynamicSeedComposer,
    private val resolver: SeedPriceResolver,
    private val bindingRepo: PriceSeedBindingRepository,
    private val gateway: AffiliateGateway,
    private val ingestService: ProductIngestService,
    private val skuService: SkuService,
    private val priceEngine: PriceEngine,
    private val priceService: PriceService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostConstruct
    fun logStartup() {
        log.info(
            "种子采价已启用：静态 {} 条，auto-latest={} cron={}",
            seedComposer.staticCount(),
            seedProps.autoLatest.enabled,
            seedProps.pollCron,
        )
    }

    @Scheduled(cron = "\${zdsj.price-seed.poll-cron}")
    fun pollDaily() {
        if (seedComposer.effectiveItems().isEmpty()) return
        val r = runOnce("每日")
        log.info(
            "种子采价[每日] 完成 共{} 成功{} 失败{} 批量{} 搜索{} 耗时{}ms",
            r.total, r.success, r.failed, r.batchCount, r.discoveryCount, r.durationMs,
        )
    }

    fun runOnce(trigger: String = "手动"): SeedPollResult {
        val started = System.currentTimeMillis()
        val items = seedComposer.effectiveItems()
        log.info(
            "种子采价[{}] 开始 静态{} 动态{} 合计{}",
            trigger, seedComposer.staticCount(), items.size - seedComposer.staticCount(), items.size,
        )
        var success = 0
        var failed = 0
        var batchCount = 0
        var discoveryCount = 0
        val failures = mutableListOf<String>()

        val tasks = buildTasks(items)
        val (direct, discovery) = tasks.partition { it.directItemId != null }
        batchCount = direct.size
        discoveryCount = discovery.size

        for ((platform, group) in direct.groupBy { it.platform }) {
            val ids = group.map { it.directItemId!! }.distinct()
            val batchMap = gateway.fetchItemsBatch(platform, ids, bypassCache = true)
                .data.orEmpty()
                .associateBy { it.platformItemId }
            for (task in group) {
                val id = task.directItemId!!
                val polled = runCatching {
                    val item = resolveBatchHit(platform, id, batchMap[id])
                    completePoll(task.seed, platform, item)
                }.recoverCatching { batchErr ->
                    log.info(
                        "批量直查失败，降级名称搜索 name={} platform={} id={} reason={}",
                        task.seed.name, platform.code, id, batchErr.message,
                    )
                    pollByName(task.seed, platform)
                }
                polled.onSuccess { success++ }
                    .onFailure { e ->
                        failed++
                        recordFailure(task.seed.name, platform.code, e.message, failures)
                    }
            }
        }

        for (task in discovery) {
            runCatching { pollByName(task.seed, task.platform) }
                .onSuccess { success++ }
                .onFailure { e ->
                    failed++
                    recordFailure(task.seed.name, task.platform.code, e.message, failures)
                }
        }

        return SeedPollResult(
            total = success + failed,
            success = success,
            failed = failed,
            durationMs = System.currentTimeMillis() - started,
            failures = failures,
            batchCount = batchCount,
            discoveryCount = discoveryCount,
        )
    }

    private data class PollTask(
        val seed: PriceSeedProperties.SeedItem,
        val platform: Platform,
        val directItemId: String?,
    )

    private fun buildTasks(items: List<PriceSeedProperties.SeedItem>): List<PollTask> {
        val tasks = mutableListOf<PollTask>()
        for (seed in items) {
            for (platformCode in seed.platforms) {
                val platform = Platform.fromCode(platformCode) ?: continue
                val direct = seed.directItemId(platform)
                    ?: bindingRepo.findBySeedNameAndPlatform(resolver.normalizeSeedName(seed.name), platform.code)
                        .map { it.platformItemId }
                        .orElse(null)
                        ?.takeIf { canDirectPoll(platform, it) }
                tasks.add(PollTask(seed, platform, direct))
            }
        }
        return tasks
    }

    private fun canDirectPoll(platform: Platform, itemId: String): Boolean = when (platform) {
        Platform.JD -> itemId.all { it.isDigit() }
        Platform.PDD -> PddLinkParser.isGoodsSign(itemId)
        else -> false
    }

    /** 批量未命中时，单条 fetchItem（含 jd_search 补救）再试；仍失败则名称搜索发现 */
    private fun resolveBatchHit(platform: Platform, itemId: String, fromBatch: AffiliateItem?): AffiliateItem {
        val priced = sequenceOf(fromBatch, gateway.fetchItem(platform, itemId, bypassCache = true).data)
            .filterNotNull()
            .firstOrNull { JdSearchRemedy.hasPrice(it) && !resolver.isMockItem(it) }
        if (priced != null) return priced
        throw IllegalStateException("批量直查无价: $itemId (${platform.code})")
    }

    @Transactional
    fun pollByName(seed: PriceSeedProperties.SeedItem, platform: Platform) {
        val item = resolver.resolve(seed.name, platform, seedProps.searchLimit)
        completePoll(seed, platform, item)
    }

    @Transactional
    fun completePoll(seed: PriceSeedProperties.SeedItem, platform: Platform, item: AffiliateItem) {
        val priceResult = recordSnapshot(item)
        resolver.touchBinding(seed.name, platform.code, ingestService.upsert(item).id!!)
        log.info(
            "种子采价成功 name={} platform={} itemId={} title={} finalPrice={}",
            seed.name, platform.code, item.platformItemId, item.title.take(40),
            priceResult.estimatedFinalPrice,
        )
    }

    private fun recordSnapshot(item: AffiliateItem): FinalPriceResult {
        val raw = ingestService.upsert(item)
        val (sku, _) = skuService.resolveAndPersist(raw.id!!, item)
        val priceResult = priceEngine.compute(item, UserAssets())
        if (!priceResult.pricePending) {
            priceService.recordSnapshot(raw.id!!, sku?.id, item.platform, priceResult)
        }
        return priceResult
    }

    private fun recordFailure(
        seedName: String,
        platformCode: String,
        message: String?,
        failures: MutableList<String>,
    ) {
        val msg = "$seedName/$platformCode: ${message ?: "unknown"}"
        if (failures.size < 50) failures.add(msg)
        log.warn("种子采价失败 {}", msg)
    }
}

data class SeedPollResult(
    val total: Int,
    val success: Int,
    val failed: Int,
    val durationMs: Long,
    val failures: List<String>,
    val batchCount: Int = 0,
    val discoveryCount: Int = 0,
)
