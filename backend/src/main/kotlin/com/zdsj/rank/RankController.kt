package com.zdsj.rank

import com.zdsj.common.ApiResponse
import com.zdsj.price.PriceSnapshotRepository
import com.zdsj.affiliate.ProductImageUrls
import com.zdsj.product.ProductImageStorageService
import com.zdsj.product.ProductMappingRepository
import com.zdsj.product.ProductRaw
import com.zdsj.product.ProductRawRepository
import com.zdsj.product.ProductSkuRepository
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal

/**
 * 低价榜（P1，PRD §11.4）：先用最近快照聚合每 SKU 最优到手价，
 * 冷启动期可由后台人工维护 20–50 个热门 SKU。
 */
@Service
class RankService(
    private val snapshotRepo: PriceSnapshotRepository,
    private val skuRepo: ProductSkuRepository,
    private val mappingRepo: ProductMappingRepository,
    private val rawRepo: ProductRawRepository,
    private val imageStorage: ProductImageStorageService,
) {
    fun phoneRank(brand: String?, priceMin: BigDecimal?, priceMax: BigDecimal?): List<Map<String, Any?>> {
        val recent = snapshotRepo.findTop200ByOrderByCapturedAtDesc()
        return recent
            .filter { it.skuId != null }
            .groupBy { it.skuId!! }
            .mapNotNull { (skuId, snaps) ->
                // 排除降级/空价写入的 0 价快照，否则 0 会被当成最低价
                val best = snaps
                    .filter { it.estimatedFinalPrice > BigDecimal.ZERO }
                    .minByOrNull { it.estimatedFinalPrice } ?: return@mapNotNull null
                val sku = skuRepo.findById(skuId).orElse(null) ?: return@mapNotNull null
                if (brand != null && !sku.brand.equals(brand, ignoreCase = true)) return@mapNotNull null
                if (priceMin != null && best.estimatedFinalPrice < priceMin) return@mapNotNull null
                if (priceMax != null && best.estimatedFinalPrice > priceMax) return@mapNotNull null
                val mappings = mappingRepo.findBySkuId(skuId)
                val riskTags = mappings.flatMap { it.riskTags }.distinct()
                val raw = rawRepo.findById(best.rawProductId).orElse(null)
                val imageRaw = pickImageRaw(raw, mappings)
                mapOf(
                    "skuId" to skuId,
                    "standardName" to sku.standardName,
                    "brand" to sku.brand,
                    "bestFinalPrice" to best.estimatedFinalPrice,
                    "platform" to best.platform,
                    "rawProductId" to best.rawProductId,
                    "platformItemId" to raw?.platformItemId,
                    "imageUrl" to imageRaw?.let { imageStorage.displayUrl(it) },
                    "title" to raw?.title,
                    "riskTags" to riskTags,
                    "capturedAt" to best.capturedAt.toString(),
                )
            }
            .sortedBy { it["bestFinalPrice"] as BigDecimal }
    }

    /** 最低价那条可能没图，从同款其它链接兜底取一张有图的 */
    private fun pickImageRaw(primary: ProductRaw?, mappings: List<com.zdsj.product.ProductMapping>): ProductRaw? {
        val candidates = buildList {
            primary?.let { add(it) }
            mappings.forEach { m ->
                rawRepo.findById(m.rawProductId).orElse(null)?.let { add(it) }
            }
        }
        return candidates.firstOrNull { hasDisplayableImage(it) }
    }

    private fun hasDisplayableImage(raw: ProductRaw): Boolean =
        imageStorage.localFileExists(raw.localImagePath) || ProductImageUrls.isLoadable(raw.imageUrl)
}

@RestController
@RequestMapping("/api/rank")
class RankController(private val rankService: RankService) {

    @GetMapping("/phone")
    fun phone(
        @RequestParam(required = false) brand: String?,
        @RequestParam(required = false, name = "price_min") priceMin: BigDecimal?,
        @RequestParam(required = false, name = "price_max") priceMax: BigDecimal?,
    ): ApiResponse<List<Map<String, Any?>>> =
        ApiResponse.ok(rankService.phoneRank(brand, priceMin, priceMax))
}
