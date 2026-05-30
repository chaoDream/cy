package com.zdsj.rank

import com.zdsj.common.ApiResponse
import com.zdsj.price.PriceSnapshotRepository
import com.zdsj.product.ProductMappingRepository
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
) {
    fun phoneRank(brand: String?, priceMin: BigDecimal?, priceMax: BigDecimal?): List<Map<String, Any?>> {
        val recent = snapshotRepo.findTop200ByOrderByCapturedAtDesc()
        return recent
            .filter { it.skuId != null }
            .groupBy { it.skuId!! }
            .mapNotNull { (skuId, snaps) ->
                val best = snaps.minByOrNull { it.estimatedFinalPrice } ?: return@mapNotNull null
                val sku = skuRepo.findById(skuId).orElse(null) ?: return@mapNotNull null
                if (brand != null && !sku.brand.equals(brand, ignoreCase = true)) return@mapNotNull null
                if (priceMin != null && best.estimatedFinalPrice < priceMin) return@mapNotNull null
                if (priceMax != null && best.estimatedFinalPrice > priceMax) return@mapNotNull null
                val riskTags = mappingRepo.findBySkuId(skuId).flatMap { it.riskTags }.distinct()
                val raw = rawRepo.findById(best.rawProductId).orElse(null)
                mapOf(
                    "skuId" to skuId,
                    "standardName" to sku.standardName,
                    "brand" to sku.brand,
                    "bestFinalPrice" to best.estimatedFinalPrice,
                    "platform" to best.platform,
                    "rawProductId" to best.rawProductId,
                    "platformItemId" to raw?.platformItemId,
                    "imageUrl" to raw?.imageUrl,
                    "title" to raw?.title,
                    "riskTags" to riskTags,
                    "capturedAt" to best.capturedAt.toString(),
                )
            }
            .sortedBy { it["bestFinalPrice"] as BigDecimal }
    }
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
