package com.zdsj.product

import com.zdsj.affiliate.Platform
import com.zdsj.affiliate.ProductImageUrls
import com.zdsj.affiliate.jd.JdImageResolver
import com.zdsj.affiliate.provider.AffiliateGateway
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.client.RestClient
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * 商品主图：优先读本地落盘文件；无本地缓存时拉外网并尝试写盘；最后返回 PNG 占位图。
 * 本接口必须始终返回图片字节，不可抛未捕获异常（否则小程序 downloadFile 收到 JSON 500）。
 */
@RestController
@RequestMapping("/api/product")
class ProductImageController(
    private val rawRepo: ProductRawRepository,
    private val gateway: AffiliateGateway,
    private val jdImageResolver: JdImageResolver,
    private val imageStorage: ProductImageStorageService,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val restClient = RestClient.create()

    @GetMapping("/image")
    fun image(
        @RequestParam platform: String,
        @RequestParam("item_id") itemId: String,
    ): ResponseEntity<ByteArray> = runCatching {
        serveImage(platform, itemId)
    }.getOrElse { e ->
        log.warn("商品图代理异常 platform={} itemId={} err={}", platform, itemId.take(32), e.message)
        placeholderResponse("商品")
    }

    private fun serveImage(platform: String, itemId: String): ResponseEntity<ByteArray> {
        val raw = rawRepo.findByPlatformAndPlatformItemId(platform, itemId).orElse(null)

        readCachedBytes(platform, itemId, raw)?.let { (bytes, mediaType) ->
            return imageResponse(bytes, mediaType)
        }

        resolveImageUrl(platform, itemId, raw)?.let { url ->
            val persisted = raw?.let { runCatching { imageStorage.persistFromUrl(it, url) }.getOrNull() }
            readCachedBytes(platform, itemId, persisted ?: raw)?.let { (bytes, mediaType) ->
                return imageResponse(bytes, mediaType)
            }
            fetchImageBytes(url, platform)?.let { bytes ->
                raw?.let { runCatching { imageStorage.persistFromUrl(it, url) } }
                return imageResponse(bytes, guessMediaType(url))
            }
        }

        return placeholderResponse(raw?.title ?: "商品")
    }

    private fun readCachedBytes(platform: String, itemId: String, raw: ProductRaw?): Pair<ByteArray, String>? {
        imageStorage.readLocalBytes(raw?.localImagePath)?.let {
            return it to imageStorage.guessMediaType(raw?.localImagePath)
        }
        imageStorage.readLocalBytes(platform, itemId)?.let {
            val path = imageStorage.resolveDiskPath(platform, itemId)
            return it to imageStorage.guessMediaType(path)
        }
        return null
    }

    private fun imageResponse(bytes: ByteArray, mediaType: String): ResponseEntity<ByteArray> =
        ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(mediaType))
            .header(HttpHeaders.CACHE_CONTROL, "public, max-age=86400")
            .body(bytes)

    private fun placeholderResponse(title: String): ResponseEntity<ByteArray> =
        ResponseEntity.ok()
            .contentType(MediaType.IMAGE_PNG)
            .body(buildPlaceholderPng(title))

    /** 京东数字 SKU 优先走页面公开主图（快），再走联盟 API（慢） */
    private fun resolveImageUrl(platform: String, itemId: String, raw: ProductRaw?): String? {
        raw?.imageUrl
            ?.takeIf { ProductImageUrls.isLoadable(it) }
            ?.let { return it }

        val p = Platform.fromCode(platform) ?: return null
        if (p == Platform.JD && itemId.all { it.isDigit() }) {
            jdImageResolver.resolveMainImage(itemId)
                ?.takeIf { ProductImageUrls.isLoadable(it) }
                ?.let { return it }
        }

        return gateway.resolveImage(p, itemId)
            ?.takeIf { ProductImageUrls.isLoadable(it) }
    }

    private fun fetchImageBytes(url: String, platform: String): ByteArray? {
        val normalized = if (url.startsWith("//")) "https:$url" else url
        val referer = when (platform.lowercase()) {
            "pdd" -> "https://mobile.yangkeduo.com/"
            else -> "https://m.jd.com/"
        }
        return runCatching {
            restClient.get()
                .uri(normalized)
                .header("User-Agent", "Mozilla/5.0")
                .header("Referer", referer)
                .retrieve()
                .body(ByteArray::class.java)
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    private fun guessMediaType(url: String): String = when {
        url.contains(".png", ignoreCase = true) -> "image/png"
        url.contains(".webp", ignoreCase = true) -> "image/webp"
        else -> "image/jpeg"
    }

    private fun buildPlaceholderPng(title: String): ByteArray = runCatching {
        val size = 400
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color(0xF2, 0xF3, 0xF5)
        g.fillRect(0, 0, size, size)
        g.color = Color(0xE5, 0xE6, 0xEB)
        g.fillRoundRect(40, 40, 320, 240, 24, 24)
        g.color = Color(0x8A, 0x90, 0x99)
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 28)
        g.drawString("No Image", 150, 210)
        g.color = Color(0x4E, 0x59, 0x69)
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 22)
        val safe = title.filter { it.code < 128 }.take(16).ifBlank { "Product" }
        g.drawString(safe, (size - g.fontMetrics.stringWidth(safe)) / 2, 320)
        g.dispose()
        ByteArrayOutputStream().use { out ->
            ImageIO.write(img, "png", out)
            out.toByteArray()
        }
    }.getOrElse { ByteArray(0) }
}
