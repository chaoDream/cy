package com.zdsj.catalog

import com.zdsj.affiliate.Platform
import com.zdsj.affiliate.provider.AffiliateGateway
import com.zdsj.config.CatalogSyncProperties
import com.zdsj.sku.SkuCatalogReader
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 定时从京东 / 拼多多搜索拉取商品，充盈 catalog_brand / catalog_model 目录。
 */
@Component
@ConditionalOnProperty(prefix = "zdsj.catalog-sync", name = ["enabled"], havingValue = "true")
class CatalogSyncJob(
    private val props: CatalogSyncProperties,
    private val gateway: AffiliateGateway,
    private val harvestService: CatalogHarvestService,
    private val catalogReader: SkuCatalogReader,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostConstruct
    fun logStartup() {
        log.info(
            "品牌型号目录同步已启用：keywords={} platforms={} cron={}",
            props.resolvedKeywords(catalogReader).size,
            props.platforms,
            props.pollCron,
        )
    }

    @Scheduled(cron = "\${zdsj.catalog-sync.poll-cron}")
    fun pollScheduled() {
        val r = runOnce("定时")
        log.info(
            "目录同步[定时] 完成 keywords={} items={} brands+={} models+={} 失败={} 耗时{}ms",
            r.keywordCount, r.itemCount, r.brandsUpserted, r.modelsUpserted, r.failed, r.durationMs,
        )
    }

    fun runOnce(trigger: String = "手动"): CatalogSyncResult {
        val started = System.currentTimeMillis()
        val keywords = props.resolvedKeywords(catalogReader)
        log.info("目录同步[{}] 开始 keywords={}", trigger, keywords.size)
        var itemCount = 0
        var brandsUpserted = 0
        var modelsUpserted = 0
        var failed = 0
        val failures = mutableListOf<String>()

        for (keyword in keywords) {
            for (platformCode in props.platforms) {
                val platform = Platform.fromCode(platformCode) ?: continue
                runCatching {
                    val items = gateway.search(platform, keyword, props.searchLimit).data.orEmpty()
                    for (item in items) {
                        itemCount++
                        val c = harvestService.harvest(item)
                        brandsUpserted += c.brands
                        modelsUpserted += c.models
                    }
                }.onFailure {
                    failed++
                    val msg = "$keyword/$platformCode: ${it.message}"
                    if (failures.size < 50) failures.add(msg)
                    log.warn("目录同步失败 {}", msg)
                }
            }
        }

        catalogReader.refresh()
        return CatalogSyncResult(
            keywordCount = keywords.size,
            itemCount = itemCount,
            brandsUpserted = brandsUpserted,
            modelsUpserted = modelsUpserted,
            failed = failed,
            durationMs = System.currentTimeMillis() - started,
            failures = failures,
        )
    }
}

data class CatalogSyncResult(
    val keywordCount: Int,
    val itemCount: Int,
    val brandsUpserted: Int,
    val modelsUpserted: Int,
    val failed: Int,
    val durationMs: Long,
    val failures: List<String>,
)
