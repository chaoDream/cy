package com.zdsj.product

import com.zdsj.affiliate.AffiliateItem
import com.zdsj.affiliate.JdLinkParser
import com.zdsj.affiliate.JdSearchRemedy
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
import org.slf4j.LoggerFactory
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

/** 商品分析核心结果（不含 AI，首屏快速返回） */
data class AnalysisCoreResult(
    val productInfo: Map<String, Any?>,
    val skuInfo: Map<String, Any?>,
    val priceInfo: FinalPriceResult,
    val trendInfo: PriceTrend,
    val riskInfo: List<String>,
    val crossPlatform: List<Map<String, Any?>>,
    val shopAlternative: Map<String, Any?>?,
    val cpsLink: String?,
)

private data class PreparedAnalysis(
    val platform: Platform,
    val item: AffiliateItem,
    val raw: ProductRaw,
    val sku: com.zdsj.product.ProductSku?,
    val mapping: com.zdsj.product.ProductMapping,
    val priceResult: FinalPriceResult,
    val trend: PriceTrend,
    val riskTags: List<String>,
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
    private val imageStorage: ProductImageStorageService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** POST /api/link/parse —— 解析链接/淘口令/分享文本 */
    fun parseLink(linkText: String, userKey: String? = null): ParseResult {
        if (linkText.isBlank()) {
            throw BizException(ErrorCode.LINK_EMPTY, "请粘贴京东或拼多多手机商品链接")
        }
        val detected = gateway.detect(linkText)
            ?: throw BizException(ErrorCode.PLATFORM_UNSUPPORTED, "当前版本优先支持京东和拼多多")
        val (platform, _) = detected
        val item = gateway.fetchFromShareText(linkText, userKey).data
            ?: throw BizException(ErrorCode.PARSE_FAILED, "暂时没识别出来，可以换一个链接试试")
        val enriched = enrichShareMeta(item, linkText)
        val priced = ensureJdPricedItem(enriched, linkText)

        // 非手机品类：mock 演示数据严格拦截；真实联盟数据放行
        val parsed = skuService.parseItem(priced)
        if (affiliateProps.mock && parsed.brand == null && !looksLikePhone(priced.title)) {
            throw BizException(ErrorCode.NOT_PHONE_CATEGORY, "该商品暂不在手机品类范围内")
        }

        val raw = ingestService.upsert(priced)
        skuService.resolveAndPersist(raw.id!!, priced)
        return ParseResult(
            platform = platform.code,
            itemId = priced.platformItemId,
            rawProductId = raw.id!!,
            productInfo = productInfoMap(raw, priced),
            parseStatus = "success",
        )
    }

    /** GET /api/product/analysis —— 核心数据（价格/趋势/风险/跨平台），不含 AI */
    fun analyze(platformCode: String, itemId: String, assets: UserAssets, userKey: String? = null): AnalysisCoreResult {
        val ctx = prepareAnalysis(platformCode, itemId, assets)
        val alt = samePlatformUpgrade(ctx.platform, ctx.sku, ctx.item, assets, userKey)
        val cross = crossPlatform(ctx.platform, ctx.sku, ctx.item, assets, userKey)
        val purchase = resolvePurchaseLink(ctx.platform, ctx.item, userKey)
        return toCoreResult(ctx, cross, alt, purchase)
    }

    /** GET /api/product/ai-recommendation —— AI 购买建议（独立加载，不阻塞首屏） */
    fun aiRecommendation(
        platformCode: String,
        itemId: String,
        assets: UserAssets,
        forceRule: Boolean = false,
    ): AiResult {
        val ctx = prepareAnalysis(platformCode, itemId, assets)
        val input = toAiInput(ctx)
        if (forceRule) return aiService.ruleBasedRecommendation(input)
        return runCatching { aiService.analyze(skuId = ctx.sku?.id, input = input) }
            .getOrElse { e ->
                log.warn("AI 推荐接口异常，降级到规则推理: {}", e.message)
                aiService.ruleBasedRecommendation(input)
            }
    }

    private fun prepareAnalysis(platformCode: String, itemId: String, assets: UserAssets): PreparedAnalysis {
        val platform = Platform.fromCode(platformCode)
            ?: throw BizException(ErrorCode.PLATFORM_UNSUPPORTED, "不支持的平台")
        val item = resolveAffiliateItem(platform, itemId)
        val raw = ingestService.upsert(item)
        val (sku, mapping) = skuService.resolveAndPersist(raw.id!!, item)
        val priceResult = priceEngine.compute(item, assets)
        if (!priceResult.pricePending) {
            priceService.recordSnapshot(raw.id!!, sku?.id, platform.code, priceResult)
        }
        val trend = priceService.trend(sku?.id, priceResult.estimatedFinalPrice)
        val riskTags = mapping.riskTags.toMutableList()
        if (item.shopType == "thirdparty" && riskTags.none { it.contains("第三方") }) {
            riskTags += "第三方店铺"
        }
        return PreparedAnalysis(platform, item, raw, sku, mapping, priceResult, trend, riskTags)
    }

    private fun toAiInput(ctx: PreparedAnalysis) = AiInput(
        title = ctx.item.title,
        standardSku = ctx.sku?.standardName,
        platform = ctx.platform.code,
        shopType = ctx.item.shopType,
        currentFinalPrice = ctx.priceResult.estimatedFinalPrice,
        low30 = ctx.trend.low30,
        low90 = ctx.trend.low90,
        nearLow = ctx.trend.nearLow,
        fakeDiscount = ctx.trend.fakeDiscount,
        riskTags = ctx.riskTags,
        discountBreakdown = ctx.priceResult.included.map { "${it.name} -${it.amount}" },
    )

    private fun toCoreResult(
        ctx: PreparedAnalysis,
        cross: List<Map<String, Any?>>,
        alt: Map<String, Any?>?,
        purchase: Pair<String?, String>,
    ) = AnalysisCoreResult(
        productInfo = productInfoMap(ctx.raw, ctx.item) + mapOf(
            "rawProductId" to ctx.raw.id,
            "purchaseLinkType" to purchase.second,
        ),
        skuInfo = mapOf(
            "standardName" to (ctx.sku?.standardName ?: ctx.item.title),
            "confidence" to ctx.mapping.confidence,
            "needConfirm" to (ctx.mapping.confidence == "low"),
        ),
        priceInfo = ctx.priceResult,
        trendInfo = ctx.trend,
        riskInfo = ctx.riskTags,
        crossPlatform = cross,
        shopAlternative = alt,
        cpsLink = purchase.first,
    )

    /**
     * 购买链接：优先联盟 CPS 短链；失败或 mock 时回落商品页/分享链（须在京东 App 可打开）。
     * @return Pair(链接, 类型 cps | product_page | share_url)
     */
    private fun resolvePurchaseLink(platform: Platform, item: AffiliateItem, userKey: String?): Pair<String?, String> {
        val cps = gateway.buildCpsLink(platform, item.platformItemId, userKey).data
        if (!cps.isNullOrBlank() && !isMockOrInvalidPurchaseUrl(cps) && jdPurchaseLinkTrusted(platform, cps, item.platformItemId)) {
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

    /**
     * 京东 u.jd.com 短链在 sceneId 错配时可能落到活动页；无法确认指向当前 SKU 时改用 item.jd.com 商品页。
     */
    private fun jdPurchaseLinkTrusted(platform: Platform, link: String, skuId: String): Boolean {
        if (platform != Platform.JD || !skuId.all { it.isDigit() }) return true
        if (link.contains(skuId)) return true
        if (link.contains("item.jd.com/$skuId") || link.contains("item.m.jd.com")) return true
        if (link.contains("union-click.jd.com") && link.contains("e=")) return true
        if (link.contains("u.jd.com") || link.contains("3.cn/")) return false
        return true
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
                "imageUrl" to imageStorage.displayUrl(raw),
                "rawPrice" to item.rawPrice,
            )
        }
    }

    private val TRUSTED_SHOP_TYPES = setOf("self", "flagship")

    /**
     * 同平台店铺类型切换：搜索同款的另一种店铺类型版本。
     * 当前是第三方 → 找自营/旗舰；当前是自营/旗舰 → 找第三方。
     */
    private fun samePlatformUpgrade(
        platform: Platform,
        sku: com.zdsj.product.ProductSku?,
        selfItem: AffiliateItem,
        assets: UserAssets,
        userKey: String? = null,
    ): Map<String, Any?>? {
        val keyword = sku?.let { "${it.brand} ${it.model}" }
            ?: extractBrandModel(selfItem.title)
            ?: return null
        val isTrusted = selfItem.shopType in TRUSTED_SHOP_TYPES
        val hits = gateway.search(platform, keyword, 10).data ?: return null
        val candidates = hits.filter {
            it.platformItemId != selfItem.platformItemId &&
                if (isTrusted) it.shopType !in TRUSTED_SHOP_TYPES else it.shopType in TRUSTED_SHOP_TYPES
        }
        if (candidates.isEmpty()) return null
        // 用精简的品牌+型号做匹配，同品牌候选直接取第一个（搜索词已足够精准）
        val candidate = com.zdsj.affiliate.JdGoodsMatcher.pickBest(keyword, candidates)
            ?: candidates.firstOrNull()
            ?: return null
        val raw = ingestService.upsert(candidate)
        val price = priceEngine.compute(candidate, assets)
        return mapOf(
            "platform" to platform.code,
            "itemId" to raw.platformItemId,
            "title" to candidate.title,
            "shopName" to candidate.shopName,
            "shopType" to candidate.shopType,
            "estimatedFinalPrice" to price.estimatedFinalPrice,
        )
    }

    /** 从冗长标题提取精简搜索词：取前3个有意义的 token，足够搜到同款又不至于过滤掉结果 */
    private fun extractBrandModel(title: String): String? {
        val tokens = com.zdsj.affiliate.JdGoodsMatcher.tokenize(title)
        if (tokens.isEmpty()) return null
        return tokens.take(2).joinToString(" ")
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
            jdSearchRemedy(shareTitle, itemId.takeIf { it.all(Char::isDigit) })
                ?.let { searched -> return preferPricedItem(searched, cached, platform) }
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

    /** 有价优先；合并分享元数据（sourceUrl / _shareTitle / _jdSkuId） */
    private fun preferPricedItem(candidate: AffiliateItem, cached: AffiliateItem?, platform: Platform): AffiliateItem {
        val shareTitle = resolveShareTitle(cached)
        val merged = mergeShareMeta(candidate, cached)
        val numericSku = cached?.platformItemId?.takeIf { it.all(Char::isDigit) }
            ?: merged.couponInfo["_jdSkuId"] as? String
        if (shareTitle != null && !com.zdsj.affiliate.JdGoodsMatcher.matchesShareTitle(shareTitle, merged)) {
            if (platform == Platform.JD) {
                jdSearchRemedy(shareTitle, numericSku)
                    ?.let { return mergeShareMeta(it, cached) }
            }
            return merged.copy(rawPrice = BigDecimal.ZERO)
        }
        if (JdSearchRemedy.hasPrice(merged)) return merged
        if (platform == Platform.JD) {
            jdSearchRemedy(shareTitle, numericSku)
                ?.let { return mergeShareMeta(it, cached) }
        }
        return merged
    }

    /**
     * 链接解析：数字 SKU 直查无价时，搜索落库带价的 materialId，避免分析页长期 rawPrice=0。
     */
    private fun ensureJdPricedItem(item: AffiliateItem, linkText: String): AffiliateItem {
        if (item.platform != Platform.JD.code || JdSearchRemedy.hasPrice(item)) return item
        val shareTitle = (item.couponInfo["_shareTitle"] as? String)
            ?: JdLinkParser.extractShareTitle(linkText)
            ?: item.title.takeIf { it.isNotBlank() && !it.startsWith("http") && it != "京东商品" }
        val numericSku = item.platformItemId.takeIf { it.all(Char::isDigit) }
        val found = jdSearchRemedy(shareTitle, numericSku) ?: return item
        return mergeShareMeta(found, item)
    }

    /** 京东搜索补救：完整标题 → KeywordDegrader → 品牌+型号 → 数字 SKU */
    private fun jdSearchRemedy(shareTitle: String?, numericSku: String? = null): AffiliateItem? {
        if (shareTitle.isNullOrBlank() && numericSku.isNullOrBlank()) return null
        for (keyword in JdSearchRemedy.recallKeywords(shareTitle, numericSku)) {
            val hits = gateway.search(Platform.JD, keyword, 8).data ?: continue
            val picked = when {
                !shareTitle.isNullOrBlank() -> JdSearchRemedy.pickPricedMatch(shareTitle, hits)
                else -> JdSearchRemedy.pickPricedBySku(hits, numericSku!!)
            } ?: continue
            return picked
        }
        return null
    }

    private fun mergeShareMeta(item: AffiliateItem, cached: AffiliateItem?): AffiliateItem {
        if (cached == null) return item
        val meta = item.couponInfo.toMutableMap()
        (cached.couponInfo["_shareTitle"] as? String)?.let { meta.putIfAbsent("_shareTitle", it) }
        resolveShareTitle(cached)?.let { meta.putIfAbsent("_shareTitle", it) }
        cached.platformItemId.takeIf { it.all(Char::isDigit) }?.let { meta.putIfAbsent("_jdSkuId", it) }
        (cached.couponInfo["_jdSkuId"] as? String)?.let { meta.putIfAbsent("_jdSkuId", it) }
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

    private fun productInfoMap(raw: ProductRaw, item: AffiliateItem): Map<String, Any?> = mapOf(
        "platform" to item.platform,
        "itemId" to item.platformItemId,
        "title" to item.title,
        "imageUrl" to imageStorage.displayUrl(raw),
        "shopName" to item.shopName,
        "shopType" to item.shopType,
        "rawPrice" to item.rawPrice.takeIf { it > BigDecimal.ZERO },
        "activityTags" to com.zdsj.affiliate.ActivityTags.sanitize(item.activityTags),
        "sourceUrl" to item.sourceUrl,
    )
}
