package com.zdsj.sku

import com.zdsj.catalog.CatalogBrandRepository
import com.zdsj.catalog.CatalogModelRepository
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.concurrent.atomic.AtomicReference

@Service
class SkuCatalogReader(
    private val brandRepo: CatalogBrandRepository,
    private val modelRepo: CatalogModelRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val cache = AtomicReference(SkuCatalogSnapshot.EMPTY)

    @PostConstruct
    fun init() = refresh()

    fun snapshot(): SkuCatalogSnapshot = cache.get()

    fun refresh() {
        cache.set(loadFromDb())
        log.info(
            "SKU 目录已刷新：品牌 {} 个，型号 {} 条",
            cache.get().brandAliasToCanonical.values.distinct().size,
            cache.get().modelsByBrand.values.sumOf { it.size },
        )
    }

    fun activeBrandNames(): List<String> =
        brandRepo.findByStatus("active").map { it.canonicalName }

    private fun loadFromDb(): SkuCatalogSnapshot {
        val brands = brandRepo.findByStatus("active")
        val aliasMap = linkedMapOf<String, String>()
        for (b in brands) {
            aliasMap[b.canonicalName.lowercase()] = b.canonicalName
            b.aliases.forEach { alias ->
                val key = alias.trim().lowercase()
                if (key.isNotBlank()) aliasMap[key] = b.canonicalName
            }
        }
        val modelsByBrand = brands.associate { brand ->
            val models = modelRepo.findByBrandAndStatus(brand.canonicalName, "active")
                .map { it.model }
                .sortedByDescending { it.length }
            brand.canonicalName to models
        }.filterValues { it.isNotEmpty() }
        return SkuCatalogSnapshot(aliasMap, modelsByBrand)
    }
}
