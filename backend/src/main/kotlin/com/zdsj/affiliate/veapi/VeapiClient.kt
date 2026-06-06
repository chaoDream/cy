package com.zdsj.affiliate.veapi

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.zdsj.config.AffiliateProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.util.UriComponentsBuilder

/**
 * 维易 VEAPI 客户端：统一 GET 调用 + 返回值拆包。
 *
 * 鉴权（已核对官方文档「怎样接入」）：
 *   - 公共参数仅 vekey（必填）+ secret（可选，明文加强参数，非 HMAC 签名算法）。
 *   - 维易当前不要求复杂 sign，故本客户端不实现签名拼接；如官方后续要求，
 *     在 [appendAuth] 内补充即可，不影响业务层。
 *
 * 返回值统一信封：{"error":"0","msg":"...","data":{...}}，error=="0" 即成功。
 */
@Component
class VeapiClient(
    private val props: AffiliateProperties,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val restClient = RestClient.create()

    private val veapi get() = props.veapi

    fun isConfigured(): Boolean = veapi.vekey.isNotBlank()

    /**
     * 调用维易接口，返回 data 节点；error!="0" 或网络异常返回 null（由 Gateway 决定降级）。
     * @param path 形如 "/jd/promotiongoodsinfo"
     * @param params 业务参数（不含 vekey/secret，会自动追加）
     */
    fun get(path: String, params: Map<String, String?>): JsonNode? {
        if (!isConfigured()) return null
        val builder = UriComponentsBuilder.fromHttpUrl(veapi.baseUrl.trimEnd('/') + path)
        appendAuth(builder)
        params.forEach { (k, v) -> if (!v.isNullOrBlank()) builder.queryParam(k, v) }
        val uri = builder.encode().build().toUri()

        val raw = request(uri) ?: run {
            // 官方文档以 http 为主；https 在本环境常握手失败，自动降级 http 重试一次
            val httpUri = uri.toString().replaceFirst("https://", "http://")
            if (httpUri != uri.toString()) request(java.net.URI.create(httpUri)) else null
        } ?: return null

        val root = runCatching { objectMapper.readTree(raw) }.getOrNull()
        if (root == null) {
            log.warn("[veapi] 响应非 JSON path={} body={}", path, raw.take(300))
            return null
        }
        val error = root.path("error").asText("")
        if (error != "0") {
            log.warn("[veapi] 业务错误 path={} error={} msg={}", path, error, root.path("msg").asText(""))
            return null
        }
        return root.path("data").takeIf { !it.isMissingNode && !it.isNull } ?: root
    }

    private fun appendAuth(builder: UriComponentsBuilder) {
        builder.queryParam("vekey", veapi.vekey)
        if (veapi.secret.isNotBlank()) builder.queryParam("secret", veapi.secret)
    }

    private fun request(uri: java.net.URI): String? =
        runCatching { restClient.get().uri(uri).retrieve().body(String::class.java) }
            .getOrElse {
                log.warn("[veapi] 请求失败 uri={} err={}", uri, it.message)
                null
            }
}
