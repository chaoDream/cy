package com.zdsj.affiliate.veapi

import com.fasterxml.jackson.databind.JsonNode
import com.zdsj.affiliate.AffiliateItem
import com.zdsj.affiliate.JdGoodsMatcher
import com.zdsj.affiliate.JdLinkParser
import com.zdsj.affiliate.JdSearchRemedy
import com.zdsj.affiliate.PddLinkParser
import com.zdsj.affiliate.Platform
import com.zdsj.affiliate.jd.JdUnionClient
import com.zdsj.affiliate.provider.AffiliateContext
import com.zdsj.affiliate.provider.AffiliateProvider
import com.zdsj.affiliate.provider.AuthMode
import com.zdsj.config.AffiliateProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 维易 VEAPI 提供商：一个 provider 通吃京东 + 拼多多（可扩展淘宝等）。
 * 支持自有联盟号（self）与维易公共/多用户号（public：附带 sessionkey + pid）。
 */
@Component
class VeapiProvider(
    private val client: VeapiClient,
    private val mapper: VeapiMapper,
    private val props: AffiliateProperties,
    private val jdUnionClient: JdUnionClient,
) : AffiliateProvider {

    private val veapi get() = props.veapi
    private val log = LoggerFactory.getLogger(javaClass)

    override fun name() = "veapi"

    override fun supports(platform: Platform): Boolean {
        if (!client.isConfigured()) return false
        return platform == Platform.JD || platform == Platform.PDD
    }

    override fun healthy(platform: Platform): Boolean = client.isConfigured()

    override fun extractItemId(platform: Platform, linkText: String): String? = when (platform) {
        Platform.JD -> JdLinkParser.extractItemId(linkText)
        Platform.PDD -> PddLinkParser.extractItemId(linkText)
    }

    override fun fetchItem(ctx: AffiliateContext, itemId: String): AffiliateItem? = when (ctx.platform) {
        Platform.JD -> fetchJdItem(itemId)
        Platform.PDD -> fetchPddItem(ctx, itemId)
    }

    override fun fetchFromShareText(linkText: String, ctx: AffiliateContext?): AffiliateItem? = when {
        JdLinkParser.isJdShareText(linkText) -> fetchJdFromShare(linkText)
        PddLinkParser.isPddShareText(linkText) -> fetchPddFromShare(linkText, ctx)
        else -> null
    }

    /**
     * 京东分享解析顺序：
     * 1. 文案内数字 SKU 直查
     * 2. 短链 HTTP 跳转 / 转链解析 SKU
     * 3. 「」分享标题搜索并匹配（禁止用 URL 当关键词，否则会搜出无关商品）
     */
    private fun fetchJdFromShare(linkText: String): AffiliateItem? {
        val shareUrl = JdLinkParser.extractUrl(linkText)
        val shareTitle = JdLinkParser.extractShareTitle(linkText)

        JdLinkParser.extractItemId(linkText)?.takeIf { it.all(Char::isDigit) }?.let { sku ->
            fetchJdItem(sku, shareTitle)?.takeIf { JdSearchRemedy.hasPrice(it) }
                ?.let { return attachShareMeta(it, shareUrl, shareTitle) }
        }

        if (shareUrl != null) {
            resolveSkuFromShortUrl(shareUrl)?.let { sku ->
                fetchJdItem(sku, shareTitle)?.takeIf { JdSearchRemedy.hasPrice(it) }
                    ?.let { return attachShareMeta(it, shareUrl, shareTitle) }
            }
        }

        if (!shareTitle.isNullOrBlank()) {
            val numericSku = JdLinkParser.extractItemId(linkText)?.takeIf { it.all(Char::isDigit) }
                ?: shareUrl?.let { resolveSkuFromShortUrl(it) }
            searchByShareTitle(shareTitle, shareUrl, numericSku)?.let { return it }
        }
        return null
    }

    private fun resolveSkuFromShortUrl(url: String): String? {
        JdLinkParser.extractItemIdFromUrl(url)?.let { return it }
        jdUnionClient.resolveSkuIdFromUrl(url)?.let { return it }
        val data = client.get(
            "/jd/prombysubuid",
            mapOf("materialId" to url, "sceneId" to "1"),
        ) ?: return null
        data.path("skuId").asText(null)?.takeIf { it.all(Char::isDigit) }?.let { return it }
        val clickUrl = data.path("clickURL").asText(null) ?: data.path("shortURL").asText(null)
        if (clickUrl != null) {
            JdLinkParser.extractItemIdFromUrl(clickUrl)?.let { return it }
            jdUnionClient.resolveSkuIdFromUrl(clickUrl)?.let { return it }
        }
        return null
    }

    private fun searchByShareTitle(shareTitle: String, shareUrl: String?, numericSku: String? = null): AffiliateItem? {
        for (keyword in JdSearchRemedy.recallKeywords(shareTitle, numericSku)) {
            val picked = JdSearchRemedy.pickPricedMatch(shareTitle, jdSearch(keyword, 8)) ?: continue
            return attachShareMeta(picked, shareUrl, shareTitle)
        }
        return null
    }

    private fun attachShareMeta(item: AffiliateItem, shareUrl: String?, shareTitle: String?): AffiliateItem {
        val meta = item.couponInfo.toMutableMap()
        if (!shareTitle.isNullOrBlank()) meta["_shareTitle"] = shareTitle
        return item.copy(
            sourceUrl = shareUrl ?: item.sourceUrl,
            couponInfo = meta,
        )
    }

    override fun search(ctx: AffiliateContext, keyword: String, limit: Int): List<AffiliateItem> = when (ctx.platform) {
        Platform.JD -> jdSearch(keyword, limit)
        Platform.PDD -> pddSearch(keyword, limit)
    }

    override fun fetchEliteGoods(ctx: AffiliateContext, eliteId: Int, limit: Int): List<AffiliateItem> = when (ctx.platform) {
        Platform.JD -> jdJingfen(eliteId, limit)
        Platform.PDD -> pddRecommend(eliteId, limit, ctx)
    }

    override fun fetchMaterialRecommend(ctx: AffiliateContext, eliteId: Int, limit: Int): List<AffiliateItem> = when (ctx.platform) {
        Platform.JD -> jdMaterialRecommend(eliteId, limit, ctx)
        Platform.PDD -> pddRecommend(eliteId, limit, ctx)
    }

    override fun fetchItemsBatch(ctx: AffiliateContext, itemIds: List<String>): List<AffiliateItem> = when (ctx.platform) {
        Platform.JD -> jdPromotionBatch(itemIds)
        Platform.PDD -> pddGoodsBatch(itemIds, ctx)
        else -> emptyList()
    }

    override fun buildCpsLink(ctx: AffiliateContext, itemId: String): String? = when (ctx.platform) {
        Platform.JD -> buildJdLink(ctx, itemId)
        Platform.PDD -> buildPddLink(ctx, itemId)
    }

    override fun resolveNumericItemId(ctx: AffiliateContext, itemId: String): String? = when (ctx.platform) {
        Platform.JD -> when {
            itemId.all(Char::isDigit) -> itemId
            else -> resolveJdNumericSkuId(itemId)
        }
        else -> itemId.takeIf { it.all(Char::isDigit) }
    }

    // ---- JD ----

    /** 维易 /jd/getNumid：联盟字符串 ID → 京东数字 skuId */
    private fun resolveJdNumericSkuId(strId: String): String? {
        val data = client.get("/jd/getNumid", mapOf("strid" to strId)) ?: return null
        return data.path("skuid").asText(null)?.takeIf { it.isNotBlank() && it.all(Char::isDigit) }
    }

    /**
     * 数字 SKU 查价：promotiongoodsinfo（官方常 403）→ jd_search 补救。
     * @param shareTitle 可选，用于搜索匹配与品牌+型号召回
     */
    private fun fetchJdItem(itemId: String, shareTitle: String? = null): AffiliateItem? {
        val numericSku = itemId.takeIf { it.all(Char::isDigit) } ?: resolveJdNumericSkuId(itemId)
        if (numericSku != null) {
            val fromPromo = client.get("/jd/promotiongoodsinfo", mapOf("skuIds" to numericSku))
                ?.let { listNodes(it).firstNotNullOfOrNull { node -> mapper.mapJdPromotionGoods(node) } }
            if (fromPromo != null && JdSearchRemedy.hasPrice(fromPromo)) {
                return fromPromo.copy(platformItemId = numericSku)
            }
            searchJdForSku(numericSku, shareTitle)?.let { return it.copy(platformItemId = numericSku) }
            fromPromo?.let { return it.copy(platformItemId = numericSku) }
        }
        if (!shareTitle.isNullOrBlank()) {
            searchByShareTitle(shareTitle, null)?.let { return it }
        }
        return null
    }

    /** promotiongoodsinfo 不可用时的 jd_search 补救 */
    private fun searchJdForSku(numericSku: String, shareTitle: String?): AffiliateItem? {
        for (keyword in JdSearchRemedy.recallKeywords(shareTitle, numericSku)) {
            val hits = jdSearch(keyword, 8)
            val picked = when {
                !shareTitle.isNullOrBlank() -> JdSearchRemedy.pickPricedMatch(shareTitle, hits)
                else -> JdSearchRemedy.pickPricedBySku(hits, numericSku)
            } ?: continue
            return attachNumericSkuMeta(picked, numericSku)
        }
        return null
    }

    private fun attachNumericSkuMeta(item: AffiliateItem, numericSku: String): AffiliateItem {
        val meta = item.couponInfo.toMutableMap()
        meta["_jdSkuId"] = numericSku
        return item.copy(couponInfo = meta)
    }

    /**
     * 京东搜索：京东对「12GB+256GB 国行」这类精确规格后缀常返回 0 条，
     * 故按 JdSearchRemedy 逐级降级关键词（完整词 → 去规格 → 品牌+型号）重试，命中即返回。
     * 这样在 Gateway 落到 mock / 触发熔断之前就能拿到真实结果。
     */
    private fun jdSearch(keyword: String, limit: Int): List<AffiliateItem> {
        val pageSize = limit.coerceIn(1, 30).toString()
        for (kw in JdSearchRemedy.recallKeywords(keyword)) {
            val data = client.get("/jd/jd_search", mapOf("keyword" to kw, "pageSize" to pageSize)) ?: continue
            val items = listNodes(data).mapNotNull { mapper.mapJdSearchGoods(it) }
            if (items.isNotEmpty()) return items
        }
        return emptyList()
    }

    /**
     * 京粉精选：维易 `/jd/jingfengoods`（等同官方 jingfen.query / goods.elite.get）。
     * 文档亦称 elitegoods，故对 jingfengoods 无结果时尝试 `/jd/elitegoods`。
     */
    private fun jdJingfen(eliteId: Int, limit: Int): List<AffiliateItem> {
        val params = mutableMapOf(
            "eliteId" to eliteId.toString(),
            "pageIndex" to "1",
            "pageSize" to limit.coerceIn(1, 50).toString(),
        )
        veapi.jd.positionId.takeIf { it.contains('_') }?.let { params["pid"] = it }
        for (path in listOf("/jd/jingfengoods", "/jd/elitegoods")) {
            val data = client.get(path, params) ?: continue
            val items = listNodes(data).mapNotNull { mapper.mapJdSearchGoods(it) }
            if (items.isNotEmpty()) return items
        }
        return emptyList()
    }

    /** 维易批量推广查价：/jd/promotiongoodsinfo，skuIds 逗号分隔，最多 100 个/次 */
    private fun jdPromotionBatch(skuIds: List<String>): List<AffiliateItem> {
        if (skuIds.isEmpty()) return emptyList()
        return skuIds.distinct().filter { it.all(Char::isDigit) }.chunked(50).flatMap { chunk ->
            val data = client.get("/jd/promotiongoodsinfo", mapOf("skuIds" to chunk.joinToString(",")))
                ?: return@flatMap emptyList()
            listNodes(data).mapNotNull { mapper.mapJdPromotionGoods(it) }
        }
    }

    /** 维易批量详情：/pdd/pdd_goodssearch + goods_sign_list */
    private fun pddGoodsBatch(goodsSigns: List<String>, ctx: AffiliateContext): List<AffiliateItem> {
        if (goodsSigns.isEmpty()) return emptyList()
        return goodsSigns.distinct().filter { PddLinkParser.isGoodsSign(it) }.chunked(20).flatMap { chunk ->
            val params = mutableMapOf("goods_sign_list" to chunk.joinToString(","))
            attachVeapiPddParams(params, ctx)
            val data = client.get("/pdd/pdd_goodssearch", params) ?: return@flatMap emptyList()
            listNodes(data).mapNotNull { mapper.mapPddGoods(it) }
        }
    }

    /**
     * 千人千面物料推荐：维易 `/jd/jd_materialquery`（官方 goods.recommend.get）。
     * 用户表亦称 `/jd/recommend`，故作为别名回退。eliteId：1猜你喜欢、2实时热销、3大额券、4=9.9包邮。
     */
    private fun jdMaterialRecommend(eliteId: Int, limit: Int, ctx: AffiliateContext): List<AffiliateItem> {
        val params = mutableMapOf(
            "eliteId" to eliteId.toString(),
            "pageIndex" to "1",
            "pageSize" to limit.coerceIn(1, 10).toString(),
        )
        veapi.jd.positionId.takeIf { it.contains('_') }?.let { params["pid"] = it }
        pseudoDeviceId(ctx.userKey)?.let { (type, id) ->
            params["userIdType"] = type.toString()
            params["userId"] = id
        }
        for (path in listOf("/jd/jd_materialquery", "/jd/recommend")) {
            val data = client.get(path, params) ?: continue
            val items = listNodes(data).mapNotNull { mapper.mapJdSearchGoods(it) }
            if (items.isNotEmpty()) return items
        }
        return emptyList()
    }

    private fun pseudoDeviceId(userKey: String?): Pair<Int, String>? {
        val key = userKey?.takeIf { it.isNotBlank() } ?: return null
        val md5 = java.security.MessageDigest.getInstance("MD5")
            .digest(key.toByteArray())
            .joinToString("") { "%02X".format(it) }
        return 128 to md5
    }

    /**
     * 维易京东单品转链：jd_prombyuid + sceneId=2 + unionId（必填）。
     * 见 https://www.veapi.cn/apidoc/jingdonglianmeng/217
     */
    private fun buildJdLink(ctx: AffiliateContext, itemId: String): String? {
        if (!itemId.all { it.isDigit() }) return null
        val unionId = veapi.jd.unionId.takeIf { it.isNotBlank() }
            ?: props.jd.unionId.takeIf { it.isNotBlank() }
        if (unionId == null) return null

        val sceneId = veapi.jd.sceneId.coerceIn(1, 2)
        val materials = if (sceneId == 2) {
            listOf(
                "https://item.jd.com/$itemId.html",
                itemId,
            )
        } else {
            listOf("https://jingfen.jd.com/detail/$itemId.html")
        }

        for (material in materials) {
            val params = mutableMapOf(
                "materialId" to material,
                "unionId" to unionId,
                "sceneId" to sceneId.toString(),
                "chainType" to veapi.jd.chainType.coerceIn(1, 3).toString(),
            )
            attachVeapiJdAuth(params, ctx)
            val data = client.get("/jd/jd_prombyuid", params) ?: continue
            pickJdPromoUrl(data, itemId)?.let { return it }
        }
        return null
    }

    private fun attachVeapiJdAuth(params: MutableMap<String, String>, ctx: AffiliateContext) {
        veapi.jd.positionId.takeIf { it.isNotBlank() }?.let { params["positionId"] = it }
        veapi.jd.sessionkey.takeIf { it.isNotBlank() }?.let { params["sessionkey"] = it }
        if (ctx.authMode == AuthMode.PUBLIC) {
            veapi.jd.unionId.takeIf { it.isNotBlank() }?.let { params["pid"] = it }
        }
    }

    private fun pickJdPromoUrl(data: JsonNode, skuId: String): String? {
        if (data.path("trans_type").asInt(-1) == 0) return null
        val skuFromApi = data.path("skuId").asText(null)?.takeIf { it.all(Char::isDigit) }
        if (skuFromApi != null && skuFromApi != skuId) return null
        val click = data.path("clickURL").asText(null)
        val short = data.path("shortURL").asText(null)
        // chainType=1 时长链 clickURL，优先用于直达商品详情
        if (click != null && (click.contains(skuId) || click.contains("item.jd.com/$skuId") || skuFromApi == skuId)) {
            return click
        }
        if (data.path("trans_type").asInt(0) == 1 && click != null) return click
        if (skuFromApi == skuId) return click ?: short
        return null
    }

    // ---- PDD ----

    private fun fetchPddFromShare(linkText: String, ctx: AffiliateContext?): AffiliateItem? {
        val pddCtx = ctx ?: AffiliateContext(platform = Platform.PDD, authMode = pddAuthMode())
        val keywords = buildList {
            PddLinkParser.extractUrl(linkText)?.let { add(it) }
            PddLinkParser.extractShareTitle(linkText)?.let { add(it) }
            PddLinkParser.extractKeyword(linkText)?.let { add(it) }
        }.distinct().filter { it.isNotBlank() }
        for (keyword in keywords) {
            pddSearch(keyword, 1, pddCtx).firstOrNull()?.let { return it }
        }
        return null
    }

    private fun fetchPddItem(ctx: AffiliateContext, itemId: String): AffiliateItem? {
        val params = mutableMapOf("goods_sign_list" to itemId)
        if (itemId.all { it.isDigit() }) params["usenumid"] = "1"
        attachVeapiPddParams(params, ctx)
        val data = client.get("/pdd/pdd_goodssearch", params) ?: return null
        return listNodes(data).firstNotNullOfOrNull { mapper.mapPddGoods(it) }
    }

    /** 拼多多运营频道推荐（维易 `/pdd/pdd_recommend`，channel_type 对应 eliteId） */
    private fun pddRecommend(channelType: Int, limit: Int, ctx: AffiliateContext): List<AffiliateItem> {
        val params = mutableMapOf(
            "channel_type" to channelType.toString(),
            "limit" to limit.coerceIn(1, 50).toString(),
        )
        attachVeapiPddParams(params, ctx)
        val data = client.get("/pdd/pdd_recommend", params) ?: return emptyList()
        return listNodes(data).mapNotNull { mapper.mapPddGoods(it) }
    }

    private fun pddSearch(
        keyword: String,
        limit: Int,
        ctx: AffiliateContext = AffiliateContext(platform = Platform.PDD, authMode = pddAuthMode()),
    ): List<AffiliateItem> {
        val pageSize = limit.coerceIn(1, 100).toString()
        for (kw in JdSearchRemedy.recallKeywords(keyword)) {
            val params = mutableMapOf(
                "keyword" to kw,
                "page_size" to pageSize,
            )
            attachVeapiPddParams(params, ctx)
            val data = client.get("/pdd/pdd_goodssearch", params) ?: continue
            val items = listNodes(data).mapNotNull { mapper.mapPddGoods(it) }
            if (items.isNotEmpty()) return items
        }
        return emptyList()
    }

    private fun attachVeapiPddParams(params: MutableMap<String, String>, ctx: AffiliateContext) {
        val pid = veapi.pdd.pid.takeIf { it.isNotBlank() }
            ?: props.pdd.pid.takeIf { it.isNotBlank() }
        if (pid == null) {
            log.warn("[veapi] 拼多多未配置 pid，接口可能返回空（请在 zdsj.affiliate.veapi.pdd.pid 填写已备案推广位）")
            return
        }
        params["pid"] = pid
        params["p_id"] = pid
        val custom = ctx.userKey?.takeIf { it.isNotBlank() }
            ?: veapi.pdd.defaultCustomParameters.takeIf { it.isNotBlank() }
        if (custom != null) params["custom_parameters"] = custom
        if (ctx.authMode == AuthMode.PUBLIC) {
            veapi.pdd.sessionkey.takeIf { it.isNotBlank() }?.let { params["sessionkey"] = it }
        }
    }

    private fun pddAuthMode(): AuthMode =
        if (veapi.pdd.authMode.equals("public", ignoreCase = true)) AuthMode.PUBLIC else AuthMode.SELF

    private fun buildPddLink(ctx: AffiliateContext, itemId: String): String? {
        val params = mutableMapOf("goods_sign_list" to itemId)
        if (itemId.all { it.isDigit() }) params["usenumid"] = "1"
        attachVeapiPddParams(params, ctx)
        val data = client.get("/pdd/pdd_promlink", params) ?: return null
        val first = listNodes(data.path("goods_promotion_url_list").takeIf { !it.isMissingNode } ?: data)
            .firstOrNull() ?: return null
        return first.path("mobile_url").asText(null)
            ?: first.path("mobile_short_url").asText(null)
            ?: first.path("short_url").asText(null)
            ?: first.path("url").asText(null)
    }

    /** 维易 data 节点形态各异：可能是数组、含 list 的对象、或单对象。统一抽成节点列表。 */
    private fun listNodes(data: JsonNode): List<JsonNode> {
        if (data.isArray) return data.toList()
        for (key in LIST_KEYS) {
            val node = data.path(key)
            if (node.isArray) return node.toList()
        }
        return if (data.isObject && data.size() > 0) listOf(data) else emptyList()
    }

    companion object {
        private val LIST_KEYS = listOf(
            "goodsList", "goods_list", "goods_details", "list", "data", "results", "goods",
        )
    }
}
