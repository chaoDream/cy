package com.zdsj.affiliate.jd

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.zdsj.affiliate.JdLinkParser
import com.zdsj.common.BizException
import com.zdsj.common.ErrorCode
import com.zdsj.config.AffiliateProperties
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * 京东联盟开放平台客户端（routerjson + MD5 签名）。
 * 文档：https://union.jd.com/openplatform
 */
@Component
class JdUnionClient(
    private val props: AffiliateProperties,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val restClient = RestClient.create()
    private val httpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    private val jd get() = props.jd

    fun isConfigured(): Boolean =
        jd.appKey.isNotBlank() && jd.appSecret.isNotBlank() && jd.unionId.isNotBlank()

    /** 转链：短链/长链/口令分享 URL → 推广链接（含落地页信息） */
    fun convertLink(materialUrl: String, sceneId: Int? = null): JsonNode? {
        val req = mutableMapOf<String, Any>(
            "materialId" to materialUrl,
        )
        sceneId?.let { req["sceneId"] = it }
        when {
            jd.siteId.isNotBlank() -> req["siteId"] = jd.siteId
            else -> req["unionId"] = jd.unionId.toLong()
        }
        if (jd.positionId.isNotBlank()) req["positionId"] = jd.positionId

        val method = if (jd.siteId.isNotBlank()) {
            "jd.union.open.promotion.common.get"
        } else {
            "jd.union.open.promotion.byunionid.get"
        }
        return invoke(method, mapOf("promotionCodeReq" to req))
    }

    /** 按 SKU 查推广商品信息（基础权限） */
    fun queryPromotionGoodsInfo(skuIds: List<String>): JsonNode? {
        val ids = skuIds.joinToString(",")
        return invoke("jd.union.open.goods.promotiongoodsinfo.query", mapOf("skuIds" to ids))
            ?: invoke(
                "jd.union.open.goods.promotiongoodsinfo.query",
                mapOf("goodsReq" to mapOf("skuIds" to ids)),
            )
    }

    /** 关键词/SKU 商品查询 */
    fun queryGoods(skuIds: List<String>? = null, keyword: String? = null, pageSize: Int = 10): JsonNode? {
        val body = mutableMapOf<String, Any>(
            "pageIndex" to 1,
            "pageSize" to pageSize,
            "fields" to "skuId,spuId,skuName,brandName,categoryInfo,categoryName,price,imageInfo,imgUrl,shopInfo,owner,couponInfo,materialUrl",
        )
        skuIds?.takeIf { it.isNotEmpty() }?.let { body["skuIds"] = it.joinToString(",") }
        keyword?.takeIf { it.isNotBlank() }?.let { body["keyword"] = it }
        return invoke("jd.union.open.goods.query", mapOf("goodsReqDTO" to body))
    }

    /** 京粉精选（基础权限，可尝试按频道拉商品图） */
    fun queryJingfen(eliteId: Int = 3, pageSize: Int = 1, keyword: String? = null): JsonNode? {
        val req = mutableMapOf<String, Any>(
            "eliteId" to eliteId,
            "pageIndex" to 1,
            "pageSize" to pageSize,
        )
        keyword?.takeIf { it.isNotBlank() }?.let { req["keyword"] = it }
        return invoke("jd.union.open.goods.jingfen.query", mapOf("goodsReq" to req))
    }

    /** 千人千面物料推荐（goods.recommend.get）：1猜你喜欢、2实时热销、3大额券、4=9.9包邮 */
    fun queryMaterialRecommend(
        eliteId: Int,
        pageSize: Int = 10,
        userIdType: Int? = null,
        userId: String? = null,
    ): JsonNode? {
        val req = mutableMapOf<String, Any>(
            "eliteId" to eliteId,
            "pageIndex" to 1,
            "pageSize" to pageSize.coerceIn(1, 10),
        )
        if (userIdType != null && !userId.isNullOrBlank()) {
            req["userIdType"] = userIdType
            req["userId"] = userId
        }
        if (jd.positionId.isNotBlank()) req["positionId"] = jd.positionId
        return invoke("jd.union.open.goods.recommend.get", mapOf("goodsReq" to req))
    }

    /** 关键词搜索（goods.search 接口，与 goods.query 不同） */
    fun searchGoods(keyword: String, pageSize: Int = 10): JsonNode? {
        val req = mapOf(
            "keyword" to keyword,
            "pageIndex" to 1,
            "pageSize" to pageSize,
        )
        return invoke("jd.union.open.goods.query", mapOf("goodsReqDTO" to req))
            ?: invoke("jd.union.open.goods.search", mapOf("goodsReq" to req))
    }

    /** 跟随 HTTP 跳转 / 3.cn 页面解析，从落地页提取 item.jd.com SKU */
    fun resolveSkuIdFromUrl(url: String): String? {
        JdLinkParser.extractItemIdFromUrl(url)?.let { return it }
        return try {
            val response = httpClient.send(
                HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            val finalUrl = response.uri().toString()
            JdLinkParser.extractItemIdFromUrl(finalUrl)?.let { return it }

            val body = response.body() ?: ""
            // 联盟详情页 HTML 内嵌 item.jd.com 链接
            Regex("""item\.jd\.com/(\d+)\.html""", RegexOption.IGNORE_CASE)
                .find(body)?.groupValues?.get(1)?.let { return it }
            Regex("""hrl='([^']+)'""").find(body)?.groupValues?.get(1)?.let { hrl ->
                resolveSkuIdFromUrl(hrl)?.let { return it }
            }
            JdLinkParser.extractItemId(body)?.let { return it }
            null
        } catch (e: Exception) {
            log.warn("短链跳转解析失败: {} {}", url, e.message)
            null
        }
    }

    private fun invoke(method: String, body: Map<String, Any?>): JsonNode? {
        if (!isConfigured()) {
            throw BizException(ErrorCode.AFFILIATE_ERROR, "京东联盟未配置")
        }
        val paramJson = objectMapper.writeValueAsString(body)
        val system = linkedMapOf(
            "method" to method,
            "app_key" to jd.appKey,
            "timestamp" to nowTimestamp(),
            "format" to "json",
            "v" to "1.0",
            "sign_method" to "md5",
            "360buy_param_json" to paramJson,
        )
        system["sign"] = JdUnionSigner.sign(system, jd.appSecret)

        val form = LinkedMultiValueMap<String, String>()
        system.forEach { (k, v) -> form.add(k, v) }

        val raw = restClient.post()
            .uri(API_URL)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .body(String::class.java)
            ?: throw BizException(ErrorCode.AFFILIATE_ERROR, "京东联盟无响应")

        val root = objectMapper.readTree(raw)
        val envelope = findEnvelope(root, method)
        if (envelope == null) {
            log.error("京东联盟未知响应: {}", raw.take(800))
            throw BizException(ErrorCode.AFFILIATE_ERROR, "京东联盟响应异常")
        }
        val apiCode = envelope.path("code").asText("")
        if (apiCode != "0") {
            val msg = envelope.path("zh_desc").asText(envelope.path("en_desc").asText("京东联盟调用失败"))
            log.warn("京东联盟 API 错误 method={} code={} msg={}", method, apiCode, msg)
            throw BizException(ErrorCode.AFFILIATE_ERROR, msg)
        }
        val resultRaw = listOf("result", "queryResult", "getResult", "promotionCodeResp")
            .firstNotNullOfOrNull { key ->
                envelope.path(key).takeIf { !it.isMissingNode && !it.isNull }
            }
        return unwrapResult(resultRaw)
    }

    /** 京东开放平台响应 key 存在 response / responce 两种拼写 */
    private fun findEnvelope(root: JsonNode, method: String): JsonNode? {
        val base = method.replace('.', '_')
        for (suffix in listOf("_response", "_responce")) {
            val node = root.path(base + suffix)
            if (!node.isMissingNode) return node
        }
        return null
    }

    private fun unwrapResult(resultNode: JsonNode?): JsonNode? {
        if (resultNode == null || resultNode.isMissingNode || resultNode.isNull) return null
        val parsed = when {
            resultNode.isTextual -> objectMapper.readTree(resultNode.asText())
            else -> resultNode
        }
        val bizCode = when {
            parsed.has("code") && parsed.path("code").isNumber -> parsed.path("code").asInt()
            parsed.has("code") -> parsed.path("code").asText("0").toIntOrNull() ?: 0
            else -> 200
        }
        if (bizCode == 403) {
            log.warn("京东联盟无接口权限 code=403 message={}", parsed.path("message").asText(""))
            return null
        }
        if (bizCode != 200 && bizCode != 0) {
            log.warn("京东联盟业务错误 code={} msg={}", bizCode, parsed.path("message").asText(""))
            return null
        }
        return parsed.path("data").takeIf { !it.isMissingNode && !it.isNull } ?: parsed
    }

    private fun nowTimestamp(): String =
        ZonedDateTime.now(ZoneId.of("Asia/Shanghai"))
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

    companion object {
        private const val API_URL = "https://api.jd.com/routerjson"
    }
}
