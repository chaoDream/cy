package com.zdsj.product

import com.zdsj.affiliate.AffiliateItem
import com.zdsj.affiliate.Platform
import com.zdsj.affiliate.ProductImageUrls
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
        val enriched = enrichShareMeta(item, linkText)
        val itemId = enriched.platformItemId

        // 非手机品类：mock 演示数据严格拦截；真实联盟数据放行
        val parsed = skuService.parseTitle(item.title)
        if (affiliateProps.mock && parsed.brand == null && !looksLikePhone(item.title)) {
            throw BizException(ErrorCode.NOT_PHONE_CATEGORY, "该商品暂不在手机品类范围内")
        }

        val raw = ingestService.upsert(enriched)
        skuService.resolveAndPersist(raw.id!!, enriched)
        return ParseResult(
            platform = platform.code,
            itemId = itemId,
            rawProductId = raw.id!!,
            productInfo = productInfoMap(enriched),
            parseStatus = "success",
        )
    }

    /** GET /api/product/analysis —— 组装核心转化页所需全部数据 */
    fun analyze(platformCode: String, itemId: String, assets: UserAssets, userKey: String? = null): AnalysisResult {
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
        val cross = crossPlatform(platform, sku, item, assets, userKey)
        val purchase = resolvePurchaseLink(platform, item, userKey)

        return AnalysisResult(
            productInfo = productInfoMap(item) + mapOf(
                "rawProductId" to raw.id,
                "purchaseLinkType" to purchase.second,
            ),
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
            cpsLink = purchase.first,
        )
    }

    /**
     * 购买链接：优先联盟 CPS 短链；失败或 mock 时回落商品页/分享链（须在京东 App 可打开）。
     * @return Pair(链接, 类型 cps | product_page | share_url)
     */
    private fun resolvePurchaseLink(platform: Platform, item: AffiliateItem, userKey: String?): Pair<String?, String> {
        val cps = gateway.buildCpsLink(platform, item.platformItemId, userKey).data
        if (!cps.isNullOrBlank() && !isMockOrInvalidPurchaseUrl(cps)) {
            return cps to "cps"
        }
        when (platform) {
            Platform.JD -> {
                if (item.platformItemId.all { it.isDigit() }) {
                    return "https://item.jd.com/${item.platformItemId}.html" to "product_page"
                }
                item.sourceUrl?.takeIf { it.contains("jd.com") || it.contains("3.cn") }?.let {
                    return it to "share_url"
                }
            }
            Platform.PDD -> {
                item.sourceUrl?.takeIf { it.isNotBlank() }?.let { return it to "share_url" }
            }
        }
        return cps to if (cps.isNullOrBlank()) "none" else "cps"
    }

    private fun isMockOrInvalidPurchaseUrl(url: String): Boolean =
        url.contains("example.com", ignoreCase = true) ||
            url.contains("mock", ignoreCase = true)

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
        userKey: String? = null,
    ): List<Map<String, Any?>> {
        // 召回词降维：剔除颜色/容量/版本，避免精准匹配原 SKU（合规导购方案 §3.1）
        val keyword = sku?.let { "${it.brand} ${it.model}" }
            ?: com.zdsj.affiliate.KeywordDegrader.degrade(selfItem.title)
            ?: selfItem.title
        val recallTitle = (selfItem.couponInfo["_shareTitle"] as? String)?.takeIf { it.isNotBlank() }
            ?: selfItem.title
        return Platform.entries.filter { it != self }.mapNotNull { other ->
            val hits = gateway.search(other, keyword).data ?: return@mapNotNull null
            val candidate = com.zdsj.affiliate.JdGoodsMatcher.pickBest(recallTitle, hits)
                ?: hits.firstOrNull()
                ?: return@mapNotNull null
            if (!com.zdsj.affiliate.JdGoodsMatcher.matchesShareTitle(recallTitle, candidate)) return@mapNotNull null
            val price = priceEngine.compute(candidate, assets)
            mapOf(
                "platform" to other.code,
                "itemId" to candidate.platformItemId,
                "shopName" to candidate.shopName,
                "shopType" to candidate.shopType,
                "estimatedFinalPrice" to price.estimatedFinalPrice,
                "cpsLink" to resolvePurchaseLink(other, candidate, userKey).first,
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

    private fun fetchLiveItem(platform: Platform, itemId: String, cached: AffiliateItem?): AffiliateItem? {
        val shareTitle = resolveShareTitle(cached)
        if (platform == Platform.JD) {
            rebuildJdShareText(cached)?.let { shareText ->
                gateway.fetchFromShareText(shareText).data
                    ?.takeIf { shareTitle == null || com.zdsj.affiliate.JdGoodsMatcher.matchesShareTitle(shareTitle, it) }
                    ?.let { resolved -> return preferPricedItem(resolved, cached, platform) }
            }
            if (!shareTitle.isNullOrBlank()) {
                gateway.search(platform, shareTitle, limit = 8).data
                    ?.let { hits -> com.zdsj.affiliate.JdGoodsMatcher.pickBest(shareTitle, hits) }
                    ?.takeIf { com.zdsj.affiliate.JdGoodsMatcher.matchesShareTitle(shareTitle, it) }
                    ?.let { searched -> return preferPricedItem(searched, cached, platform) }
            }
        }
        val fallback = gateway.fetchItem(platform, itemId, bypassCache = true).data
        return fallback
            ?.takeIf { shareTitle == null || com.zdsj.affiliate.JdGoodsMatcher.matchesShareTitle(shareTitle, it) }
            ?.let { preferPricedItem(it, cached, platform) }
    }

    /** 短链或错误 SKU：用库内分享 URL + 标题重新解析 */
    private fun rebuildJdShareText(cached: AffiliateItem?): String? {
        val shareUrl = cached?.sourceUrl?.takeIf {
            com.zdsj.affiliate.JdLinkParser.isJdShareText(it) || it.contains("3.cn") || it.contains("u.jd.com")
        }
        val shareTitle = resolveShareTitle(cached)
        if (shareUrl == null && shareTitle == null) return null
        return buildString {
            append("【京东】")
            shareUrl?.let { append(it).append(' ') }
            shareTitle?.let { append('「').append(it).append('」') }
        }.trim()
    }

    /** 分享标题：优先 _shareTitle，其次「」内文案，最后用库内 title（短链解析常只落了标题） */
    private fun resolveShareTitle(cached: AffiliateItem?): String? {
        if (cached == null) return null
        (cached.couponInfo["_shareTitle"] as? String)?.takeIf { it.isNotBlank() }?.let { return it }
        com.zdsj.affiliate.JdLinkParser.extractShareTitle(cached.title)?.let { return it }
        return cached.title.takeIf { it.isNotBlank() && !it.startsWith("http") && it != "京东商品" }
    }

    /** 有价优先；合并分享元数据（sourceUrl / _shareTitle） */
    private fun preferPricedItem(candidate: AffiliateItem, cached: AffiliateItem?, platform: Platform): AffiliateItem {
        val shareTitle = resolveShareTitle(cached)
        val merged = mergeShareMeta(candidate, cached)
        if (shareTitle != null && !com.zdsj.affiliate.JdGoodsMatcher.matchesShareTitle(shareTitle, merged)) {
            if (platform == Platform.JD) {
                gateway.search(platform, shareTitle, limit = 8).data
                    ?.let { hits -> com.zdsj.affiliate.JdGoodsMatcher.pickBest(shareTitle, hits) }
                    ?.takeIf { com.zdsj.affiliate.JdGoodsMatcher.matchesShareTitle(shareTitle, it) }
                    ?.let { return mergeShareMeta(it, cached) }
            }
            return merged.copy(rawPrice = java.math.BigDecimal.ZERO)
        }
        if (merged.rawPrice.compareTo(java.math.BigDecimal.ZERO) > 0) return merged
        if (shareTitle != null && platform == Platform.JD) {
            gateway.search(platform, shareTitle, limit = 8).data
                ?.let { hits -> com.zdsj.affiliate.JdGoodsMatcher.pickBest(shareTitle, hits) }
                ?.takeIf {
                    com.zdsj.affiliate.JdGoodsMatcher.matchesShareTitle(shareTitle, it) &&
                        it.rawPrice.compareTo(java.math.BigDecimal.ZERO) > 0
                }
                ?.let { return mergeShareMeta(it, cached) }
        }
        return merged
    }

    private fun mergeShareMeta(item: AffiliateItem, cached: AffiliateItem?): AffiliateItem {
        if (cached == null) return item
        val meta = item.couponInfo.toMutableMap()
        (cached.couponInfo["_shareTitle"] as? String)?.let { meta.putIfAbsent("_shareTitle", it) }
        resolveShareTitle(cached)?.let { meta.putIfAbsent("_shareTitle", it) }
        return item.copy(
            sourceUrl = cached.sourceUrl?.takeIf { it.isNotBlank() } ?: item.sourceUrl,
            couponInfo = meta,
        )
    }

    private fun enrichShareMeta(item: AffiliateItem, linkText: String): AffiliateItem {
        val shareUrl = com.zdsj.affiliate.JdLinkParser.extractUrl(linkText)
        val shareTitle = com.zdsj.affiliate.JdLinkParser.extractShareTitle(linkText)
        val meta = item.couponInfo.toMutableMap()
        if (!shareTitle.isNullOrBlank()) meta["_shareTitle"] = shareTitle
        return item.copy(
            sourceUrl = shareUrl ?: item.sourceUrl,
            couponInfo = meta,
        )
    }

    private fun needsAffiliateRefresh(item: AffiliateItem): Boolean {
        val shareTitle = (item.couponInfo["_shareTitle"] as? String)?.takeIf { it.isNotBlank() }
        if (shareTitle != null && !com.zdsj.affiliate.JdGoodsMatcher.matchesShareTitle(shareTitle, item)) {
            return true
        }
        return item.rawPrice.compareTo(BigDecimal.ZERO) == 0 || !ProductImageUrls.isLoadable(item.imageUrl)
    }

    private fun hasPrice(item: AffiliateItem): Boolean =
        item.rawPrice.compareTo(BigDecimal.ZERO) > 0

    private fun isRicherThanCached(fresh: AffiliateItem, cached: AffiliateItem): Boolean {
        val freshHasPrice = hasPrice(fresh)
        val cachedHasPrice = hasPrice(cached)
        val freshHasImage = ProductImageUrls.isLoadable(fresh.imageUrl)
        val cachedHasImage = ProductImageUrls.isLoadable(cached.imageUrl)
        // 纠正错误 SKU：有价或标题更接近分享标题时覆盖库内残缺记录
        val freshTitleScore = titleMatchScore(fresh.title, cached)
        val cachedTitleScore = titleMatchScore(cached.title, cached)
        val shareTitle = resolveShareTitle(cached)
        val freshMatchesShare = shareTitle == null ||
            com.zdsj.affiliate.JdGoodsMatcher.matchesShareTitle(shareTitle, fresh)
        if (!freshMatchesShare) return false
        return (freshHasPrice && !cachedHasPrice) ||
            (freshHasImage && !cachedHasImage) ||
            (freshHasPrice && freshHasImage) ||
            (freshHasPrice && freshTitleScore > cachedTitleScore) ||
            (fresh.platformItemId != cached.platformItemId && freshHasPrice)
    }

    private fun titleMatchScore(title: String, cached: AffiliateItem): Int {
        val shareTitle = resolveShareTitle(cached) ?: return 0
        return com.zdsj.affiliate.JdGoodsMatcher.tokenize(shareTitle)
            .count { token -> title.contains(token, ignoreCase = true) }
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

    private fun productImageProxy(platform: String, itemId: String): String {
        val encoded = java.net.URLEncoder.encode(itemId, Charsets.UTF_8)
        return "/api/product/image?platform=$platform&item_id=$encoded"
    }
}
