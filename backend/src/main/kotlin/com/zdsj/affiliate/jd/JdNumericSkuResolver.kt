package com.zdsj.affiliate.jd

import com.zdsj.affiliate.veapi.VeapiClient
import com.zdsj.config.AffiliateProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 联盟字符串 ID → 京东数字 SKU。
 * 顺序：已是数字 → 维易 getNumid → HTTP 跳转解析（jingfen/短链）→ 维易 getidfromlink。
 * getNumid 不可用时不抛错，尽力免费路径后返回 null。
 */
@Component
class JdNumericSkuResolver(
    private val veapiClient: VeapiClient,
    private val jdUnionClient: JdUnionClient,
    private val props: AffiliateProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun resolve(strId: String, hintUrl: String? = null): String? {
        if (strId.all { it.isDigit() }) return strId
        if (strId.contains('_')) {
            resolveViaGetNumid(strId)?.let { return it }
            resolveViaUrlRedirect(strId, hintUrl)?.let { return it }
            resolveViaGetIdFromLink(strId, hintUrl)?.let { return it }
        }
        return null
    }

    /** 维易 /jd/getNumid */
    private fun resolveViaGetNumid(strId: String): String? {
        if (!veapiClient.isConfigured()) return null
        val data = veapiClient.get("/jd/getNumid", mapOf("strid" to strId)) ?: return null
        return data.path("skuid").asText(null)?.takeIf { it.isNotBlank() && it.all(Char::isDigit) }
    }

    /** 跟随 jingfen 详情页 / 搜索 materialUrl 跳转，从落地页提取 item.jd.com/{sku} */
    private fun resolveViaUrlRedirect(strId: String, hintUrl: String?): String? {
        for (url in candidateUrls(strId, hintUrl)) {
            val sku = runCatching { jdUnionClient.resolveSkuIdFromUrl(url) }.getOrNull()
            if (sku != null && sku.all { it.isDigit() }) {
                log.info("[jd-sku] URL 跳转解析成功 strId={} sku={}", strId.take(24), sku)
                return sku
            }
        }
        return null
    }

    /** 维易 /jd/getidfromlink（需 unionId） */
    private fun resolveViaGetIdFromLink(strId: String, hintUrl: String?): String? {
        if (!veapiClient.isConfigured()) return null
        val unionId = props.veapi.jd.unionId.takeIf { it.isNotBlank() }
            ?: props.jd.unionId.takeIf { it.isNotBlank() }
            ?: return null
        for (url in candidateUrls(strId, hintUrl)) {
            val data = veapiClient.get(
                "/jd/getidfromlink",
                mapOf("link" to url, "unionId" to unionId),
            ) ?: continue
            val sku = data.path("skuid").asText(null)?.takeIf { it.all(Char::isDigit) }
                ?: data.path("skuId").asText(null)?.takeIf { it.all(Char::isDigit) }
            if (sku != null) {
                log.info("[jd-sku] getidfromlink 解析成功 strId={} sku={}", strId.take(24), sku)
                return sku
            }
        }
        return null
    }

    internal fun candidateUrls(strId: String, hintUrl: String?): List<String> =
        listOfNotNull(hintUrl, jingfenDetailUrl(strId))
            .map { normalizeHttpUrl(it) }
            .distinct()

    internal fun jingfenDetailUrl(strId: String): String =
        "https://jingfen.jd.com/detail/$strId.html"

    internal fun normalizeHttpUrl(url: String): String = when {
        url.startsWith("http://", ignoreCase = true) ||
            url.startsWith("https://", ignoreCase = true) -> url
        url.contains("jd.com", ignoreCase = true) -> "https://${url.trimStart('/')}"
        else -> url
    }
}
