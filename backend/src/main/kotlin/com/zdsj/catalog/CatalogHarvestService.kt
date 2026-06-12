package com.zdsj.catalog

import com.zdsj.affiliate.AffiliateItem
import com.zdsj.sku.SkuCatalogReader
import com.zdsj.sku.SkuTitleParser
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

@Service
class CatalogHarvestService(
    private val brandRepo: CatalogBrandRepository,
    private val modelRepo: CatalogModelRepository,
    private val catalogReader: SkuCatalogReader,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val parser = SkuTitleParser()

    @Transactional
    fun harvest(item: AffiliateItem): HarvestCounts {
        if (!isPhoneRelated(item)) return HarvestCounts()
        val catalog = catalogReader.snapshot()
        val platform = item.platform
        var brands = 0
        var models = 0

        val parsed = parser.parse(item.title, item.platformBrand, catalog)
        val brand = parsed.brand
        if (brand != null) {
            brands += upsertBrand(brand, item.platformBrand, platform, item.title)
            val model = parsed.model
                ?: parser.inferModelFromTitle(item.title, brand)
            if (!model.isNullOrBlank()) {
                models += upsertModel(brand, model, platform, item.title)
            }
        } else if (!item.platformBrand.isNullOrBlank()) {
            val normalized = parser.normalizePlatformBrand(item.platformBrand, catalog)
                ?: item.platformBrand.trim()
            brands += upsertBrand(normalized, item.platformBrand, platform, item.title)
        }
        return HarvestCounts(brands = brands, models = models)
    }

    fun isPhoneRelated(item: AffiliateItem): Boolean {
        val cat = item.platformCategory?.lowercase().orEmpty()
        if (cat.contains("手机")) return true
        val title = item.title.lowercase()
        val hints = listOf(
            "手机", "iphone", "mate", "pura", "galaxy", "一加", "oneplus",
            "骁龙", "天玑", "5g", "gb", "tb",
        )
        return hints.any { title.contains(it) }
    }

    private fun upsertBrand(
        canonical: String,
        platformBrand: String?,
        platform: String,
        sampleTitle: String,
    ): Int {
        val now = OffsetDateTime.now()
        val entity = brandRepo.findByCanonicalName(canonical).orElseGet {
            CatalogBrand(canonicalName = canonical, sourcePlatform = platform)
        }
        val aliases = entity.aliases.toMutableSet()
        platformBrand?.trim()?.takeIf { it.isNotBlank() && !it.equals(canonical, ignoreCase = true) }
            ?.let { aliases.add(it) }
        entity.aliases = aliases.toMutableList()
        entity.sampleCount += 1
        entity.lastSeenAt = now
        entity.updatedAt = now
        entity.status = "active"
        if (entity.sourcePlatform == "system" && platform != "system") {
            entity.sourcePlatform = platform
        }
        brandRepo.save(entity)
        return 1
    }

    private fun upsertModel(brand: String, model: String, platform: String, sampleTitle: String): Int {
        val normalized = model.trim()
        if (normalized.length < 2) return 0
        val now = OffsetDateTime.now()
        val entity = modelRepo.findByBrandAndModel(brand, normalized).orElseGet {
            CatalogModel(brand = brand, model = normalized, sourcePlatform = platform)
        }
        entity.sampleCount += 1
        entity.lastSeenAt = now
        entity.updatedAt = now
        entity.status = "active"
        if (entity.exampleTitle.isNullOrBlank()) {
            entity.exampleTitle = sampleTitle.take(500)
        }
        modelRepo.save(entity)
        return 1
    }

    data class HarvestCounts(val brands: Int = 0, val models: Int = 0)
}
