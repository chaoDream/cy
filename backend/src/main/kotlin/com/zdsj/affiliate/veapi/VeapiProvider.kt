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

    override fun fetchFromShareText(linkText: String): AffiliateItem? = when {
        JdLinkParser.isJdShareText(linkText) -> fetchJdFromShare(linkText)
        PddLinkParser.isPddShareText(linkText) -> {
            val keyword = PddLinkParser.extractUrl(linkText) ?: PddLinkParser.extractShareTitle(linkText)
            keyword?.let { pddSearch(it, 1).firstOrNull() }
        }
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

    override fun buildCpsLink(ctx: AffiliateContext, itemId: String): String? = when (ctx.platform) {
        Platform.JD -> buildJdLink(ctx, itemId)
        Platform.PDD -> buildPddLink(ctx, itemId)
    }

    // ---- JD ----

    /**
     * 数字 SKU 查价：promotiongoodsinfo（官方常 403）→ jd_search 补救。
     * @param shareTitle 可选，用于搜索匹配与品牌+型号召回
     */
    private fun fetchJdItem(itemId: String, shareTitle: String? = null): AffiliateItem? {
        if (!itemId.all { it.isDigit() }) return null

        val fromPromo = client.get("/jd/promotiongoodsinfo", mapOf("skuIds" to itemId))
            ?.let { listNodes(it).firstNotNullOfOrNull { node -> mapper.mapJdPromotionGoods(node) } }
        if (fromPromo != null && JdSearchRemedy.hasPrice(fromPromo)) return fromPromo

        return searchJdForSku(itemId, shareTitle) ?: fromPromo
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

    private fun fetchPddItem(ctx: AffiliateContext, itemId: String): AffiliateItem? {
        val params = mutableMapOf("goods_sign_list" to itemId)
        if (itemId.all { it.isDigit() }) params["usenumid"] = "1"
        if (ctx.authMode == AuthMode.PUBLIC) {
            veapi.pdd.sessionkey.takeIf { it.isNotBlank() }?.let { params["sessionkey"] = it }
            veapi.pdd.pid.takeIf { it.isNotBlank() }?.let { params["p_id"] = it }
        }
        val data = client.get("/pdd/pdd_goodssearch", params) ?: return null
        return listNodes(data).firstNotNullOfOrNull { mapper.mapPddGoods(it) }
    }

    private fun pddSearch(keyword: String, limit: Int): List<AffiliateItem> {
        val data = client.get(
            "/pdd/pdd_goodssearch",
            mapOf("keyword" to keyword, "page_size" to limit.coerceIn(1, 100).toString()),
        ) ?: return emptyList()
        return listNodes(data).mapNotNull { mapper.mapPddGoods(it) }
    }

    private fun buildPddLink(ctx: AffiliateContext, itemId: String): String? {
        val params = mutableMapOf("goods_sign_list" to itemId)
        if (itemId.all { it.isDigit() }) params["usenumid"] = "1"
        val pid = veapi.pdd.pid.takeIf { it.isNotBlank() }
        if (pid != null) params["p_id"] = pid
        if (ctx.authMode == AuthMode.PUBLIC) {
            veapi.pdd.sessionkey.takeIf { it.isNotBlank() }?.let { params["sessionkey"] = it }
        }
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
