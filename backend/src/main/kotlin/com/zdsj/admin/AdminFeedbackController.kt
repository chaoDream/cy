package com.zdsj.admin

import com.zdsj.common.ApiResponse
import com.zdsj.common.BizException
import com.zdsj.common.ErrorCode
import com.zdsj.feedback.UserFeedbackRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/admin/feedback")
class AdminFeedbackController(
    private val jdbc: JdbcTemplate,
    private val feedbackRepo: UserFeedbackRepository,
) {

    @GetMapping("/list")
    fun list(
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(required = false) status: String?,
    ): ApiResponse<Map<String, Any?>> {
        val safePage = page.coerceAtLeast(1)
        val safeSize = size.coerceIn(1, 100)
        val offset = (safePage - 1) * safeSize

        val where = buildString {
            append("WHERE 1=1")
            if (status != null) append(" AND f.status = ?")
        }
        val args = mutableListOf<Any>()
        if (status != null) args.add(status)

        val total = jdbc.queryForObject(
            "SELECT COUNT(*) FROM user_feedback f $where",
            Long::class.java,
            *args.toTypedArray(),
        ) ?: 0L

        val listArgs = args.toMutableList()
        listArgs.add(safeSize)
        listArgs.add(offset)

        val items = jdbc.queryForList(
            """
            SELECT f.id, f.user_id, f.raw_product_id, f.feedback_type, f.feedback_content,
                   f.status, f.created_at,
                   p.title AS product_title,
                   p.platform AS product_platform,
                   p.platform_item_id AS platform_item_id,
                   p.shop_name AS shop_name,
                   p.shop_type AS shop_type,
                   p.raw_price AS raw_price,
                   p.source_url AS source_url,
                   sku.standard_name AS sku_standard_name,
                   sku.brand AS sku_brand,
                   sku.model AS sku_model,
                   sku.storage AS sku_storage,
                   snap.display_price AS snapshot_display_price,
                   snap.estimated_final_price AS snapshot_final_price
            FROM user_feedback f
            LEFT JOIN product_raw p ON p.id = f.raw_product_id
            LEFT JOIN LATERAL (
                SELECT s.standard_name, s.brand, s.model, s.storage
                FROM product_mapping m
                JOIN product_sku s ON s.id = m.sku_id
                WHERE m.raw_product_id = p.id
                ORDER BY CASE m.review_status WHEN 'approved' THEN 0 ELSE 1 END, m.updated_at DESC
                LIMIT 1
            ) sku ON TRUE
            LEFT JOIN LATERAL (
                SELECT ps.display_price, ps.estimated_final_price
                FROM price_snapshot ps
                WHERE ps.raw_product_id = p.id
                ORDER BY ps.captured_at DESC
                LIMIT 1
            ) snap ON TRUE
            $where
            ORDER BY f.created_at DESC
            LIMIT ? OFFSET ?
            """.trimIndent(),
            *listArgs.toTypedArray(),
        ).map { row -> mapFeedbackItem(row) }

        val openCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM user_feedback WHERE status = 'open'",
            Long::class.java,
        ) ?: 0L

        return ApiResponse.ok(
            mapOf(
                "items" to items,
                "total" to total,
                "page" to safePage,
                "size" to safeSize,
                "openCount" to openCount,
            ),
        )
    }

    @Transactional
    @PostMapping("/status")
    fun updateStatus(@RequestBody req: FeedbackStatusRequest): ApiResponse<Map<String, Any?>> {
        if (req.status !in setOf("open", "resolved")) {
            throw BizException(ErrorCode.PARAM_INVALID, "status 只能是 open 或 resolved")
        }
        val fb = feedbackRepo.findById(req.feedbackId)
            .orElseThrow { BizException(ErrorCode.NOT_FOUND, "反馈不存在") }
        fb.status = req.status
        feedbackRepo.save(fb)
        return ApiResponse.ok(mapOf("feedbackId" to fb.id, "status" to fb.status))
    }

    private fun typeLabel(type: String?): String = when (type) {
        "price_wrong" -> "到手价不对"
        "sku_wrong" -> "型号识别错误"
        "risk_wrong" -> "风险标签有误"
        "other" -> "其他问题"
        else -> type ?: "—"
    }

    private fun mapFeedbackItem(row: Map<String, Any?>): Map<String, Any?> {
        val platform = row["product_platform"]?.toString()
        val itemId = row["platform_item_id"]?.toString()
        val sourceUrl = row["source_url"]?.toString()
        val rawPrice = row["raw_price"]
        val snapshotFinal = row["snapshot_final_price"]
        val snapshotDisplay = row["snapshot_display_price"]
        val finalPrice = snapshotFinal ?: rawPrice
        val displayPrice = snapshotDisplay ?: rawPrice
        val shopType = row["shop_type"]?.toString()

        return mapOf(
            "id" to row["id"],
            "userId" to row["user_id"],
            "rawProductId" to row["raw_product_id"],
            "feedbackType" to row["feedback_type"],
            "feedbackTypeLabel" to typeLabel(row["feedback_type"]?.toString()),
            "feedbackContent" to row["feedback_content"],
            "status" to row["status"],
            "createdAt" to row["created_at"]?.toString(),
            "product" to mapOf(
                "title" to row["product_title"],
                "standardName" to row["sku_standard_name"],
                "brand" to row["sku_brand"],
                "model" to row["sku_model"],
                "storage" to row["sku_storage"],
                "platform" to platform,
                "platformLabel" to platformLabel(platform),
                "platformItemId" to itemId,
                "shopName" to row["shop_name"],
                "shopType" to shopType,
                "shopTypeLabel" to shopTypeLabel(shopType),
                "rawPrice" to rawPrice,
                "displayPrice" to displayPrice,
                "finalPrice" to finalPrice,
                "links" to mapOf(
                    "analysisPath" to buildAnalysisPath(platform, itemId),
                    "platformUrl" to buildPlatformUrl(platform, itemId, sourceUrl),
                    "sourceUrl" to sourceUrl?.takeIf { it.isNotBlank() },
                ),
            ),
        )
    }

    private fun platformLabel(code: String?): String = when (code) {
        "jd" -> "京东"
        "pdd" -> "拼多多"
        "tb" -> "淘宝"
        "dy" -> "抖音"
        else -> code ?: "—"
    }

    private fun shopTypeLabel(type: String?): String = when (type) {
        "self" -> "自营"
        "flagship" -> "官方旗舰店"
        "thirdparty" -> "第三方店铺"
        else -> type?.takeIf { it.isNotBlank() } ?: "—"
    }

    private fun buildAnalysisPath(platform: String?, itemId: String?): String? {
        if (platform.isNullOrBlank() || itemId.isNullOrBlank()) return null
        return "/analysis?platform=$platform&item_id=$itemId"
    }

    private fun buildPlatformUrl(platform: String?, itemId: String?, sourceUrl: String?): String? {
        if (platform == "jd" && !itemId.isNullOrBlank()) {
            return "https://item.jd.com/$itemId.html"
        }
        if (!sourceUrl.isNullOrBlank()) return sourceUrl
        return null
    }
}

data class FeedbackStatusRequest(
    val feedbackId: Long = 0,
    val status: String = "resolved",
)
