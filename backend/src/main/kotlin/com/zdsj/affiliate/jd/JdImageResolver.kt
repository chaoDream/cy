package com.zdsj.affiliate.jd

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

/**
 * 从京东移动端商品页提取主图（联盟 goods.query 无权限时的兜底）。
 * 仅解析页面内公开 imagePath，不模拟登录、不抓 Cookie。
 */
@Component
class JdImageResolver {
    private val log = LoggerFactory.getLogger(javaClass)
    private val restClient = RestClient.create()

    private val imagePathRegex = Regex("""imagePath["']?\s*:\s*["'](jfs/[^"']+)""", RegexOption.IGNORE_CASE)
    private val cdnUrlRegex = Regex("""//img(\d+)\.360buyimg\.com/[^"'\s]*?(jfs/[^"'\s]+\.(?:jpg|jpeg|png|webp))""", RegexOption.IGNORE_CASE)

    fun resolveMainImage(skuId: String): String? {
        if (!skuId.all { it.isDigit() }) return null
        val html = fetchMobilePage(skuId)
        if (html.isNullOrBlank()) return null

        imagePathRegex.find(html)?.groupValues?.get(1)?.let { path ->
            return toHttps("https://img13.360buyimg.com/n1/$path")
        }

        cdnUrlRegex.find(html)?.let { match ->
            val host = match.groupValues[1]
            val path = match.groupValues[2]
            return toHttps("https://img$host.360buyimg.com/n1/$path")
        }

        log.warn("京东主图未匹配 skuId={} htmlLen={}", skuId, html.length)
        return null
    }

    private fun fetchMobilePage(skuId: String): String? = try {
        restClient.get()
            .uri("https://item.m.jd.com/product/$skuId.html")
            .header("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 Mobile/15E148 Safari/604.1")
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .retrieve()
            .body(String::class.java)
    } catch (e: Exception) {
        log.warn("京东主图页抓取失败 skuId={} {}", skuId, e.message)
        null
    }

    private fun toHttps(url: String): String =
        if (url.startsWith("//")) "https:$url" else url
}
