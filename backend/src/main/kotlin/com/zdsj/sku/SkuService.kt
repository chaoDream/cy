package com.zdsj.sku

import com.zdsj.affiliate.AffiliateItem
import com.zdsj.product.ProductMapping
import com.zdsj.product.ProductMappingRepository
import com.zdsj.product.ProductSku
import com.zdsj.product.ProductSkuRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 标准 SKU 识别（PRD §5.2）：平台标题 → 标准属性 + 置信度 + 风险标签。
 * 品牌 / 型号目录由 [com.zdsj.catalog.CatalogSyncJob] 定时从京东、拼多多同步充盈。
 */
@Service
class SkuService(
    private val skuRepo: ProductSkuRepository,
    private val mappingRepo: ProductMappingRepository,
    private val catalogReader: SkuCatalogReader,
) {
    private val parser = SkuTitleParser()

    fun parseItem(item: AffiliateItem): SkuParseResult =
        parser.parse(item.title, item.platformBrand, catalogReader.snapshot())

    fun parseTitle(title: String, platformBrand: String? = null): SkuParseResult =
        parser.parse(title, platformBrand, catalogReader.snapshot())

    fun normalizePlatformBrand(raw: String?): String? =
        parser.normalizePlatformBrand(raw, catalogReader.snapshot())

    internal fun extractModel(title: String, brand: String?): String? =
        parser.extractModel(title, brand, catalogReader.snapshot())

    /**
     * 解析并落库映射：创建/复用 SKU，写 product_mapping。
     * 低置信度仍创建映射但 review_status=pending（进人工审核池）。
     */
    @Transactional
    fun resolveAndPersist(rawProductId: Long, item: AffiliateItem): Pair<ProductSku?, ProductMapping> {
        val existing = mappingRepo.findByRawProductId(rawProductId)
        if (existing.isPresent) {
            val m = existing.get()
            val sku = m.skuId?.let { skuRepo.findById(it).orElse(null) }
            return sku to m
        }
        val parsed = parseItem(item)
        val sku = if (parsed.brand != null && parsed.model != null) {
            val candidates = skuRepo.findByBrandAndModelAndStorage(parsed.brand, parsed.model, parsed.storage)
            candidates.firstOrNull() ?: skuRepo.save(
                ProductSku(
                    brand = parsed.brand, series = parsed.series, model = parsed.model,
                    storage = parsed.storage, color = parsed.color,
                    networkVersion = parsed.networkVersion, regionVersion = parsed.regionVersion,
                    condition = parsed.condition, packageType = parsed.packageType,
                    standardName = parsed.standardName,
                ),
            )
        } else null

        val mapping = mappingRepo.save(
            ProductMapping(
                rawProductId = rawProductId,
                skuId = sku?.id,
                confidence = parsed.confidence,
                riskTags = parsed.riskTags.toMutableList(),
                aiReason = null,
                reviewStatus = if (parsed.confidence == "low") "pending" else "approved",
            ),
        )
        return sku to mapping
    }
}
