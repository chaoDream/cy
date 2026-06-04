package com.zdsj.product

import com.zdsj.affiliate.AffiliateItem
import com.zdsj.affiliate.Platform
import com.zdsj.affiliate.provider.AffiliateGateway
import com.zdsj.ai.AiAnalysisService
import com.zdsj.ai.AiInput
import com.zdsj.ai.AiResult
import com.zdsj.common.BizException
import com.zdsj.common.ErrorCode
import com.zdsj.price.FinalPriceResult
import com.zdsj.price.PriceEngine
import com.zdsj.price.PriceService
import com.zdsj.price.PriceTrend
import com.zdsj.price.UserAssets
import com.zdsj.config.AffiliateProperties
import com.zdsj.sku.SkuService
import org.springframework.stereotype.Service
import java.math.BigDecimal

/** 链接解析结果（PRD §11.1） */
data class ParseResult(
    val platform: String,
    val itemId: String,
    val rawProductId: Long,
    val productInfo: Map<String, Any?>,
    val parseStatus: String,
)

/** 商品分析结果（PRD §11.2 / §7.3 顺序） */
data class AnalysisResult(
    val productInfo: Map<String, Any?>,
    val skuInfo: Map<String, Any?>,
    val priceInfo: FinalPriceResult,
    val trendInfo: PriceTrend,
    val riskInfo: List<String>,
    val aiRecommendation: AiResult,
    val crossPlatform: List<Map<String, Any?>>,
    val cpsLink: String?,
)

@Service
class AnalysisService(
    private val gateway: AffiliateGateway,
    private val affiliateProps: AffiliateProperties,
    private val ingestService: ProductIngestService,
    private val skuService: SkuService,
    private val priceEngine: PriceEngine,
    private val priceService: PriceService,
    private val aiService: AiAnalysisService,
    private val mappingRepo: ProductMappingRepository,
    private val rawRepo: ProductRawRepository,
) {

    /** POST /api/link/parse —— 解析链接/淘口令/分享文本 */
    fun parseLink(linkText: String): ParseResult {
        if (linkText.isBlank()) {
            throw BizException(ErrorCode.LINK_EMPTY, "请粘贴京东或拼多多手机商品链接")
        }
        val detected = gateway.detect(linkText)
            ?: throw BizException(ErrorCode.PLATFORM_UNSUPPORTED, "当前版本优先支持京东和拼多多")
        val (platform, _) = detected
        val item = gateway.fetchFromShareText(linkText).data
            ?: throw BizException(ErrorCode.PARSE_FAILED, "暂时没识别出来，可以换一个链接试试")
        val itemId = item.platformItemId

        // 非手机品类：mock 演示数据严格拦截；真实联盟数据放行
        val parsed = skuService.parseTitle(item.title)
        if (affiliateProps.mock && parsed.brand == null && !looksLikePhone(item.title)) {
            throw BizException(ErrorCode.NOT_PHONE_CATEGORY, "该商品暂不在手机品类范围内")
        }

        val raw = ingestService.upsert(item)
        skuService.resolveAndPersist(raw.id!!, item)
        return ParseResult(
            platform = platform.code,
            itemId = itemId,
            rawProductId = raw.id!!,
            productInfo = productInfoMap(item),
            parseStatus = "success",
        )
    }

    /** GET /api/product/analysis —— 组装核心转化页所需全部数据 */
    fun analyze(platformCode: String, itemId: String, assets: UserAssets): AnalysisResult {
        val platform = Platform.fromCode(platformCode)
            ?: throw BizException(ErrorCode.PLATFORM_UNSUPPORTED, "不支持的平台")
        val item = resolveAffiliateItem(platform, itemId)

        val raw = ingestService.upsert(item)
        val (sku, mapping) = skuService.resolveAndPersist(raw.id!!, item)

        // 1. 到手价（规则引擎）
        val priceResult = priceEngine.compute(item, assets)
        // 价格快照：第一天就攒
        priceService.recordSnapshot(raw.id!!, sku?.id, platform.code, priceResult)

        // 2. 趋势 + 先涨后降
        val trend = priceService.trend(sku?.id, priceResult.estimatedFinalPrice)

        // 3. 风险标签（SKU 解析 + 店铺类型）
        val riskTags = mapping.riskTags.toMutableList()
        if (item.shopType == "thirdparty" && riskTags.none { it.contains("第三方") }) {
            riskTags += "第三方店铺"
        }

        // 4. AI 建议（事实全部来自上面的工具输出）
        val ai = aiService.analyze(
            skuId = sku?.id,
            input = AiInput(
                title = item.title,
                standardSku = sku?.standardName,
                platform = platform.code,
                shopType = item.shopType,
                currentFinalPrice = priceResult.estimatedFinalPrice,
                low30 = trend.low30,
                low90 = trend.low90,
                nearLow = trend.nearLow,
                fakeDiscount = trend.fakeDiscount,
                riskTags = riskTags,
                discountBreakdown = priceResult.included.map { "${it.name} -${it.amount}" },
            ),
        )

        // 5. 跨平台同款对比
        val cross = crossPlatform(platform, sku, item, assets)

        return AnalysisResult(
            productInfo = productInfoMap(item) + mapOf("rawProductId" to raw.id),
            skuInfo = mapOf(
                "standardName" to (sku?.standardName ?: item.title),
                "confidence" to mapping.confidence,
                "needConfirm" to (mapping.confidence == "low"),
            ),
            priceInfo = priceResult,
            trendInfo = trend,
            riskInfo = riskTags,
            aiRecommendation = ai,
            crossPlatform = cross,
            cpsLink = gateway.buildCpsLink(platform, itemId).data,
        )
    }

    /** 简单搜索（关键词 → 候选） */
    fun search(keyword: String): List<Map<String, Any?>> {
        if (keyword.isBlank()) return emptyList()
        return Platform.entries.flatMap { p ->
            gateway.search(p, keyword).data ?: emptyList()
        }.map { item ->
            val raw = ingestService.upsert(item)
            mapOf(
                "platform" to item.platform,
                "itemId" to item.platformItemId,
                "rawProductId" to raw.id,
                "title" to item.title,
                "imageUrl" to item.imageUrl,
                "rawPrice" to item.rawPrice,
            )
        }
    }

    private fun crossPlatform(
        self: Platform,
        sku: com.zdsj.product.ProductSku?,
        selfItem: AffiliateItem,
        assets: UserAssets,
    ): List<Map<String, Any?>> {
        return Platform.entries.filter { it != self }.mapNotNull { other ->
            // 用标准型号在其他平台搜同款（mock 下基于标题关键词）
            val keyword = sku?.let { "${it.brand} ${it.model}" } ?: selfItem.title
            val candidate = gateway.search(other, keyword).data?.firstOrNull()
                ?: return@mapNotNull null
            val price = priceEngine.compute(candidate, assets)
            mapOf(
                "platform" to other.code,
                "itemId" to candidate.platformItemId,
                "shopName" to candidate.shopName,
                "shopType" to candidate.shopType,
                "estimatedFinalPrice" to price.estimatedFinalPrice,
                "cpsLink" to gateway.buildCpsLink(other, candidate.platformItemId).data,
            )
        }
    }

    /**
     * 分析页商品数据：库内有残缺数据（价 0 / 无图）时向联盟或京东页抓取补全，避免一直返回旧降级记录。
     */
    private fun resolveAffiliateItem(platform: Platform, itemId: String): AffiliateItem {
        val cached = rawRepo.findByPlatformAndPlatformItemId(platform.code, itemId)
            .map { ingestService.toAffiliateItem(it) }
            .orElse(null)

        if (cached != null && !needsAffiliateRefresh(cached)) return cached

        val fresh = fetchLiveItem(platform, itemId, cached)
        return when {
            fresh != null && (cached == null || isRicherThanCached(fresh, cached)) -> fresh
            cached != null -> cached
            fresh != null -> fresh
            else -> throw BizException(ErrorCode.PARSE_FAILED, "商品已下架或解析失败")
        }
    }

    private fun fetchLiveItem(platform: Platform, itemId: String, cached: AffiliateItem?): AffiliateItem? =
        gateway.fetchItem(platform, itemId, bypassCache = true).data

    private fun needsAffiliateRefresh(item: AffiliateItem): Boolean =
        item.rawPrice.compareTo(BigDecimal.ZERO) == 0 || item.imageUrl.isNullOrBlank()

    private fun isRicherThanCached(fresh: AffiliateItem, cached: AffiliateItem): Boolean {
        val freshHasPrice = fresh.rawPrice.compareTo(BigDecimal.ZERO) > 0
        val cachedHasPrice = cached.rawPrice.compareTo(BigDecimal.ZERO) > 0
        val freshHasImage = !fresh.imageUrl.isNullOrBlank()
        val cachedHasImage = !cached.imageUrl.isNullOrBlank()
        return (freshHasPrice && !cachedHasPrice) ||
            (freshHasImage && !cachedHasImage) ||
            (freshHasPrice && freshHasImage)
    }

    private fun looksLikePhone(title: String): Boolean {
        val kw = listOf("手机", "iPhone", "5G", "Pro", "Mate", "Redmi", "vivo", "OPPO", "荣耀")
        return kw.any { title.contains(it, ignoreCase = true) }
    }

    private fun productInfoMap(item: AffiliateItem): Map<String, Any?> = mapOf(
        "platform" to item.platform,
        "itemId" to item.platformItemId,
        "title" to item.title,
        "imageUrl" to productImageProxy(item.platform, item.platformItemId),
        "shopName" to item.shopName,
        "shopType" to item.shopType,
        "rawPrice" to item.rawPrice,
        "activityTags" to item.activityTags,
        "sourceUrl" to item.sourceUrl,
    )

    private fun productImageProxy(platform: String, itemId: String): String =
        "/api/product/image?platform=$platform&item_id=$itemId"
}
