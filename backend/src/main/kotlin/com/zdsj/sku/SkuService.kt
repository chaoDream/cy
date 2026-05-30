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
 * 高置信度走规则；低置信度进人工审核池（review_status=pending），可由 AI 层补充。
 */
data class SkuParseResult(
    val brand: String?,
    val series: String?,
    val model: String?,
    val storage: String?,
    val color: String?,
    val networkVersion: String?,
    val regionVersion: String?,
    val condition: String,
    val packageType: String,
    val standardName: String,
    val confidence: String,        // high/mid/low
    val riskTags: List<String>,
)

@Service
class SkuService(
    private val skuRepo: ProductSkuRepository,
    private val mappingRepo: ProductMappingRepository,
) {

    private val brands = mapOf(
        "iphone" to "Apple", "apple" to "Apple", "苹果" to "Apple",
        "华为" to "华为", "mate" to "华为", "pura" to "华为",
        "小米" to "小米", "xiaomi" to "小米", "redmi" to "Redmi",
        "vivo" to "vivo", "oppo" to "OPPO", "荣耀" to "荣耀", "honor" to "荣耀",
        "三星" to "三星", "samsung" to "三星",
    )
    private val storageRegex = Regex("""(\d+)\s?(GB|G|TB|T)\b""", RegexOption.IGNORE_CASE)
    private val refurbishWords = listOf("翻新", "官翻", "二手", "9成新", "95新", "准新")
    private val packageWords = listOf("套装", "礼盒", "全家桶", "保护壳套餐", "充电器套装")
    private val nonRegionWords = listOf("港版", "美版", "日版", "外版", "海外版", "有锁")
    private val unsealedWords = listOf("未拆封", "未激活", "无锁")

    /** 纯规则解析标题 */
    fun parseTitle(title: String): SkuParseResult {
        val lower = title.lowercase()
        val brand = brands.entries.firstOrNull { lower.contains(it.key) }?.value
        val storage = storageRegex.find(title)?.value?.uppercase()?.replace(" ", "")
        val model = extractModel(title, brand)
        val regionVersion = if (nonRegionWords.any { title.contains(it) }) "非国行" else "国行"
        val condition = when {
            refurbishWords.any { title.contains(it) } -> "翻新/二手"
            else -> "全新"
        }
        val packageType = if (packageWords.any { title.contains(it) }) "套装" else "裸机"

        val riskTags = buildList {
            if (regionVersion == "非国行") add("疑似非国行")
            if (condition == "翻新/二手") add("疑似翻新二手")
            if (packageType == "套装") add("套装不可直接比价")
            if (unsealedWords.any { title.contains(it) }) add("售后不确定")
            if (brand == null || model == null) add("规格不全")
        }

        val confidence = when {
            brand != null && model != null && storage != null && riskTags.isEmpty() -> "high"
            brand != null && model != null && (storage == null) -> "mid"
            riskTags.isNotEmpty() || brand == null || model == null -> "low"
            else -> "mid"
        }

        val standardName = listOfNotNull(brand, model, storage, regionVersion.takeIf { it == "非国行" })
            .joinToString(" ")
            .ifBlank { title.take(40) }

        return SkuParseResult(
            brand = brand, series = null, model = model, storage = storage, color = null,
            networkVersion = if (title.contains("5G")) "5G" else null,
            regionVersion = regionVersion, condition = condition, packageType = packageType,
            standardName = standardName, confidence = confidence, riskTags = riskTags,
        )
    }

    private fun extractModel(title: String, brand: String?): String? {
        val patterns = listOf(
            Regex("""iPhone\s?\d+\s?(Pro\s?Max|Pro|Plus)?""", RegexOption.IGNORE_CASE),
            Regex("""Mate\s?\d+\s?(Pro\+?|RS)?""", RegexOption.IGNORE_CASE),
            Regex("""(小米|Xiaomi)\s?\d+\s?(Ultra|Pro)?""", RegexOption.IGNORE_CASE),
            Regex("""X\d{3}\s?(Pro\+?|Ultra)?""", RegexOption.IGNORE_CASE),
        )
        return patterns.firstNotNullOfOrNull { it.find(title)?.value?.trim() }
    }

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
        val parsed = parseTitle(item.title)
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
