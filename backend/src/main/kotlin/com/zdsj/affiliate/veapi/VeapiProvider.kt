package com.zdsj.affiliate.veapi

import com.fasterxml.jackson.databind.JsonNode
import com.zdsj.affiliate.AffiliateItem
import com.zdsj.affiliate.JdLinkParser
import com.zdsj.affiliate.PddLinkParser
import com.zdsj.affiliate.Platform
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

    /** 京东分享：数字 SKU 直查；短链/口令用 URL 或标题走搜索补全 */
    private fun fetchJdFromShare(linkText: String): AffiliateItem? {
        val numericId = JdLinkParser.extractItemId(linkText)?.takeIf { it.all { c -> c.isDigit() } }
        if (numericId != null) fetchJdItem(numericId)?.let { return it }

        val url = JdLinkParser.extractUrl(linkText)
        if (url != null) {
            jdSearch(url, 1).firstOrNull()?.let { return it }
        }
        JdLinkParser.extractShareTitle(linkText)?.let { title ->
            jdSearch(title, 1).firstOrNull()?.let { return it }
        }
        return null
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

    private fun fetchJdItem(itemId: String): AffiliateItem? {
        if (!itemId.all { it.isDigit() }) return null
        val data = client.get("/jd/promotiongoodsinfo", mapOf("skuIds" to itemId)) ?: return null
        return listNodes(data).firstNotNullOfOrNull { mapper.mapJdPromotionGoods(it) }
    }

    private fun jdSearch(keyword: String, limit: Int): List<AffiliateItem> {
        val data = client.get(
            "/jd/jd_search",
            mapOf("keyword" to keyword, "pageSize" to limit.coerceIn(1, 30).toString()),
        ) ?: return emptyList()
        return listNodes(data).mapNotNull { mapper.mapJdSearchGoods(it) }
    }

    private fun buildJdLink(ctx: AffiliateContext, itemId: String): String? {
        val params = mutableMapOf(
            "materialId" to "https://item.jd.com/$itemId.html",
            "sceneId" to "1",
        )
        if (ctx.authMode == AuthMode.PUBLIC) {
            veapi.jd.sessionkey.takeIf { it.isNotBlank() }?.let { params["sessionkey"] = it }
            veapi.jd.positionId.takeIf { it.isNotBlank() }?.let { params["positionId"] = it }
            veapi.jd.unionId.takeIf { it.isNotBlank() }?.let { params["pid"] = it }
        }
        val data = client.get("/jd/prombysubuid", params) ?: return null
        return data.path("shortURL").asText(null) ?: data.path("clickURL").asText(null)
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
