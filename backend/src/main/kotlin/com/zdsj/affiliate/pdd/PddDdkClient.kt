package com.zdsj.affiliate.pdd

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.zdsj.common.BizException
import com.zdsj.common.ErrorCode
import com.zdsj.config.AffiliateProperties
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient

/**
 * 拼多多多多进宝开放平台客户端（router + MD5 签名）。
 * 文档：https://open.pinduoduo.com/#/apidocument
 */
@Component
class PddDdkClient(
    private val props: AffiliateProperties,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val restClient = RestClient.create()

    private val pdd get() = props.pdd

    fun isConfigured(): Boolean =
        pdd.clientId.isNotBlank() && pdd.clientSecret.isNotBlank()

    fun hasPid(): Boolean = pdd.pid.isNotBlank()

    /** 关键词/链接/goodsSign 搜索商品。带 custom_parameters 时返回 predict_promotion_rate 可用于比价预判 */
    fun searchGoods(
        keyword: String? = null,
        goodsSignList: List<String>? = null,
        pageSize: Int = 10,
        withCoupon: Boolean = false,
        customParameters: String? = null,
    ): JsonNode? {
        val body = mutableMapOf<String, Any?>(
            "page" to 1,
            "page_size" to pageSize.coerceIn(1, 100),
            "with_coupon" to withCoupon,
        )
        if (!keyword.isNullOrBlank()) body["keyword"] = keyword
        if (!goodsSignList.isNullOrEmpty()) body["goods_sign_list"] = goodsSignList
        if (pdd.pid.isNotBlank()) body["pid"] = pdd.pid
        if (!customParameters.isNullOrBlank()) body["custom_parameters"] = customParameters
        return invoke("pdd.ddk.goods.search", body)
    }

    /** 按 goodsSign 查详情。带 custom_parameters 时返回 predict_promotion_rate 可用于比价预判 */
    fun goodsDetail(goodsSignList: List<String>, customParameters: String? = null): JsonNode? {
        val body = mutableMapOf<String, Any?>("goods_sign_list" to goodsSignList)
        if (pdd.pid.isNotBlank()) body["pid"] = pdd.pid
        if (!customParameters.isNullOrBlank()) body["custom_parameters"] = customParameters
        return invoke("pdd.ddk.goods.detail", body)
    }

    /**
     * 用户备案：将 pid + custom_parameters 与拼多多用户绑定（多多进宝官方比价规避前置）。
     * channel_type=10、generate_we_app=true 用于小程序授权跳转。
     * 返回含 we_app_info（app_id / page_path），前端据此跳转拼多多完成授权。
     */
    fun bindAuthority(customParameters: String): JsonNode? {
        if (!hasPid()) return null
        return invoke(
            "pdd.ddk.rp.prom.url.generate",
            mapOf(
                "p_id_list" to listOf(pdd.pid),
                "channel_type" to 10,
                "generate_we_app" to true,
                "custom_parameters" to customParameters,
            ),
        )
    }

    /** 查询 pid + custom_parameters 是否已备案 */
    fun queryAuthority(customParameters: String? = null): JsonNode? {
        if (!hasPid()) return null
        val body = mutableMapOf<String, Any?>("pid" to pdd.pid)
        if (!customParameters.isNullOrBlank()) body["custom_parameters"] = customParameters
        return invoke("pdd.ddk.member.authority.query", body)
    }

    /** 生成推广链接（需配置 pid） */
    fun generatePromotionUrl(
        goodsSignList: List<String>,
        searchId: String? = null,
        generateShortUrl: Boolean = true,
        customParameters: String? = null,
    ): JsonNode? {
        if (!hasPid()) return null
        val body = mutableMapOf<String, Any?>(
            "p_id" to pdd.pid,
            "goods_sign_list" to goodsSignList,
            "generate_short_url" to generateShortUrl,
        )
        if (!searchId.isNullOrBlank()) body["search_id"] = searchId
        if (!customParameters.isNullOrBlank()) body["custom_parameters"] = customParameters
        return invoke("pdd.ddk.goods.promotion.url.generate", body)
    }

    private fun invoke(type: String, body: Map<String, Any?>): JsonNode? {
        if (!isConfigured()) {
            throw BizException(ErrorCode.AFFILIATE_ERROR, "多多进宝未配置")
        }
        val params = linkedMapOf<String, String>(
            "type" to type,
            "client_id" to pdd.clientId,
            "timestamp" to (System.currentTimeMillis() / 1000).toString(),
            "data_type" to "JSON",
        )
        body.filterValues { it != null }.forEach { (k, v) ->
            params[k] = serializeParam(v!!)
        }
        params["sign"] = PddDdkSigner.sign(params, pdd.clientSecret)

        val form = LinkedMultiValueMap<String, String>()
        params.forEach { (k, v) -> form.add(k, v) }

        val raw = restClient.post()
            .uri(API_URL)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .body(String::class.java)
            ?: throw BizException(ErrorCode.AFFILIATE_ERROR, "多多进宝无响应")

        val root = objectMapper.readTree(raw)
        val error = root.path("error_response")
        if (!error.isMissingNode && !error.isNull) {
            val msg = error.path("sub_msg").asText(error.path("error_msg").asText("多多进宝调用失败"))
            log.warn("多多进宝 API 错误 type={} code={} msg={}", type, error.path("error_code").asText(""), msg)
            throw BizException(ErrorCode.AFFILIATE_ERROR, msg)
        }

        val envelope = findEnvelope(root, type)
        if (envelope == null) {
            log.error("多多进宝未知响应 type={} body={}", type, raw.take(800))
            throw BizException(ErrorCode.AFFILIATE_ERROR, "多多进宝响应异常")
        }
        return envelope
    }

    private fun serializeParam(value: Any): String = when (value) {
        is Boolean -> if (value) "true" else "false"
        is List<*> -> objectMapper.writeValueAsString(value)
        is Map<*, *> -> objectMapper.writeValueAsString(value)
        else -> value.toString()
    }

    /** pdd.ddk.goods.search → goods_search_response */
    private fun findEnvelope(root: JsonNode, type: String): JsonNode? {
        val key = type.removePrefix("pdd.ddk.").replace('.', '_') + "_response"
        val node = root.path(key)
        return node.takeIf { !it.isMissingNode && !it.isNull }
    }

    companion object {
        private const val API_URL = "https://gw-api.pinduoduo.com/api/router"
    }
}
