package com.zdsj.price

import com.zdsj.affiliate.Platform
import com.zdsj.affiliate.provider.AffiliateGateway
import com.zdsj.catalog.CatalogHarvestService
import com.zdsj.catalog.CatalogModelRepository
import com.zdsj.config.PriceSeedProperties
import com.zdsj.sku.SkuCatalogReader
import com.zdsj.sku.SkuTitleParser
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * 按产品线槽位发现比静态 YAML 更新的机型（京东搜索 + catalog_model 目录）。
 */
@Service
class LatestModelDiscoveryService(
    private val props: PriceSeedProperties,
    private val gateway: AffiliateGateway,
    private val catalogReader: SkuCatalogReader,
    private val modelRepo: CatalogModelRepository,
    private val harvestService: CatalogHarvestService,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val parser = SkuTitleParser()

    fun discoverNewerThanStatic(static: List<PriceSeedProperties.SeedItem>): List<PriceSeedProperties.SeedItem> {
        val cfg = props.autoLatest
        if (!cfg.enabled) return emptyList()

        val slots = SeedLineRegistry.groupStaticSeeds(static)
        val existingNames = static.map { it.name.trim().lowercase() }.toMutableSet()
        val dynamic = mutableListOf<PriceSeedProperties.SeedItem>()
        val perBrand = mutableMapOf<String, Int>()

        for (slot in slots) {
            if (dynamic.size >= cfg.maxTotal) break
            if (perBrand.getOrDefault(slot.brand, 0) >= cfg.maxPerBrand) continue

            val maxStatic = slot.staticSeedNames
                .mapNotNull { modelFromSeedName(it, slot) }
                .maxOfOrNull { ModelGenerationScore.score(it, slot.lineKey) }
                ?: 0L

            val latestModel = findLatestModel(slot) ?: continue
            val latestScore = ModelGenerationScore.score(latestModel, slot.lineKey)
            if (latestScore <= maxStatic) continue

            val seedName = SeedLineRegistry.buildSeedName(latestModel, slot.storageSuffix, slot.brand)
            if (existingNames.contains(seedName.lowercase())) continue

            log.info(
                "动态种子 line={} brand={} model={} score={}>{} name={}",
                slot.lineKey, slot.brand, latestModel, latestScore, maxStatic, seedName,
            )
            dynamic.add(
                PriceSeedProperties.SeedItem(
                    name = seedName,
                    platforms = slot.platforms,
                    note = "auto-latest:${slot.lineKey}",
                ),
            )
            existingNames.add(seedName.lowercase())
            perBrand[slot.brand] = perBrand.getOrDefault(slot.brand, 0) + 1
        }
        return dynamic
    }

    private fun findLatestModel(slot: SeedLineRegistry.LineSlot): String? {
        val catalog = catalogReader.snapshot()
        val candidates = linkedSetOf<String>()

        modelRepo.findByBrandAndStatus(slot.brand, "active")
            .filter { SeedLineRegistry.matchesLine(it.model, slot) }
            .sortedByDescending { it.sampleCount }
            .take(20)
            .forEach { candidates.add(it.model.trim()) }

        val platform = Platform.fromCode(cfgPlatform()) ?: Platform.JD
        val items = gateway.search(platform, slot.searchKeyword, props.autoLatest.searchLimit).data.orEmpty()
        for (item in items) {
            if (!harvestService.isPhoneRelated(item)) continue
            val parsed = parser.parse(item.title, item.platformBrand, catalog)
            val model = parsed.model ?: parser.inferModelFromTitle(item.title, slot.brand)
            if (model.isNullOrBlank() || !SeedLineRegistry.matchesLine(model, slot)) continue
            candidates.add(model.trim())
        }

        return candidates
            .maxByOrNull { ModelGenerationScore.score(it, slot.lineKey) }
            ?.takeIf { ModelGenerationScore.score(it, slot.lineKey) > 0 }
    }

    private fun cfgPlatform(): String = props.autoLatest.platform.ifBlank { "jd" }

    /** 从静态种子名截取型号片段用于代际比较 */
    private fun modelFromSeedName(seedName: String, slot: SeedLineRegistry.LineSlot): String? {
        val catalog = catalogReader.snapshot()
        val parsed = parser.parse(seedName, null, catalog)
        parsed.model?.takeIf { SeedLineRegistry.matchesLine(it, slot) }?.let { return it }
        val beforeStorage = seedName.substringBefore(" 国行").trim()
        return beforeStorage.takeIf { SeedLineRegistry.matchesLine(it, slot) }
    }
}
