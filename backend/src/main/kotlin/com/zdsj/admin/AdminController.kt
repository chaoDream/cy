package com.zdsj.admin

import com.zdsj.common.ApiResponse
import com.zdsj.common.BizException
import com.zdsj.common.ErrorCode
import com.zdsj.product.ProductMapping
import com.zdsj.product.ProductMappingRepository
import com.zdsj.product.ProductSku
import com.zdsj.product.ProductSkuRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime

/**
 * 后台运营（PRD §4 P0：商品/价格运营 + SKU 人工审核，单人兜底质量）。
 * MVP 简化：实际部署需加管理员鉴权（独立 token / IP 白名单）。
 */
@Service
class AdminService(
    private val mappingRepo: ProductMappingRepository,
    private val skuRepo: ProductSkuRepository,
) {
    /** 待审核映射池（低置信度） */
    fun pendingMappings(): List<ProductMapping> = mappingRepo.findByReviewStatus("pending")

    @Transactional
    fun reviewMapping(mappingId: Long, skuId: Long?, confidence: String?, approved: Boolean): ProductMapping {
        val m = mappingRepo.findById(mappingId)
            .orElseThrow { BizException(ErrorCode.NOT_FOUND, "映射不存在") }
        if (skuId != null) m.skuId = skuId
        if (confidence != null) m.confidence = confidence
        m.reviewStatus = if (approved) "approved" else "rejected"
        m.updatedAt = OffsetDateTime.now()
        return mappingRepo.save(m)
    }

    @Transactional
    fun createSku(req: AdminSkuRequest): ProductSku = skuRepo.save(
        ProductSku(
            brand = req.brand, series = req.series, model = req.model,
            storage = req.storage, color = req.color, networkVersion = req.networkVersion,
            regionVersion = req.regionVersion ?: "国行",
            condition = req.condition ?: "全新", packageType = req.packageType ?: "裸机",
            standardName = req.standardName
                ?: listOfNotNull(req.brand, req.model, req.storage).joinToString(" "),
        ),
    )
}

data class AdminReviewRequest(
    val mappingId: Long = 0,
    val skuId: Long? = null,
    val confidence: String? = null,
    val approved: Boolean = true,
)

data class AdminSkuRequest(
    val brand: String = "",
    val series: String? = null,
    val model: String = "",
    val storage: String? = null,
    val color: String? = null,
    val networkVersion: String? = null,
    val regionVersion: String? = null,
    val condition: String? = null,
    val packageType: String? = null,
    val standardName: String? = null,
)

@RestController
@RequestMapping("/api/admin")
class AdminController(private val adminService: AdminService) {

    @GetMapping("/mappings/pending")
    fun pending(): ApiResponse<List<Map<String, Any?>>> {
        val list = adminService.pendingMappings().map {
            mapOf(
                "mappingId" to it.id,
                "rawProductId" to it.rawProductId,
                "skuId" to it.skuId,
                "confidence" to it.confidence,
                "riskTags" to it.riskTags,
            )
        }
        return ApiResponse.ok(list)
    }

    @PostMapping("/mappings/review")
    fun review(@RequestBody req: AdminReviewRequest): ApiResponse<Map<String, Any?>> {
        val m = adminService.reviewMapping(req.mappingId, req.skuId, req.confidence, req.approved)
        return ApiResponse.ok(mapOf("mappingId" to m.id, "reviewStatus" to m.reviewStatus))
    }

    @PostMapping("/sku/create")
    fun createSku(@RequestBody req: AdminSkuRequest): ApiResponse<Map<String, Any?>> {
        val sku = adminService.createSku(req)
        return ApiResponse.ok(mapOf("skuId" to sku.id, "standardName" to sku.standardName))
    }
}
