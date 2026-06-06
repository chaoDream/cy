package com.zdsj.product

import com.zdsj.affiliate.Platform
import com.zdsj.affiliate.ProductImageUrls
import com.zdsj.affiliate.jd.JdImageResolver
import com.zdsj.affiliate.provider.AffiliateGateway
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
 * 商品主图代理：小程序统一走 API 域名加载图片；
 * 有联盟主图则由服务端拉取并回传图片字节（小程序 image 组件不跟 302），否则返回 PNG 占位图。
 */
@RestController
@RequestMapping("/api/product")
class ProductImageController(
    private val rawRepo: ProductRawRepository,
    private val gateway: AffiliateGateway,
    private val jdImageResolver: JdImageResolver,
) {
    private val restClient = RestClient.create()

    @GetMapping("/image")
    fun image(
        @RequestParam platform: String,
        @RequestParam("item_id") itemId: String,
    ): ResponseEntity<ByteArray> {
        resolveImageUrl(platform, itemId)?.let { url ->
            fetchImageBytes(url)?.let { bytes ->
                return ResponseEntity.ok()
                    .contentType(guessMediaType(url))
                    .header(HttpHeaders.CACHE_CONTROL, "public, max-age=3600")
                    .body(bytes)
            }
        }
        val title = rawRepo.findByPlatformAndPlatformItemId(platform, itemId)
            .map { it.title }
            .orElse("商品")
        return ResponseEntity.ok()
            .contentType(MediaType.IMAGE_PNG)
            .body(buildPlaceholderPng(title))
    }

    private fun resolveImageUrl(platform: String, itemId: String): String? {
        rawRepo.findByPlatformAndPlatformItemId(platform, itemId)
            .map { it.imageUrl }
            .orElse(null)
            ?.takeIf { ProductImageUrls.isLoadable(it) }
            ?.let { return it }

        val p = Platform.fromCode(platform) ?: return null
        gateway.resolveImage(p, itemId)
            ?.takeIf { ProductImageUrls.isLoadable(it) }
            ?.let { return it }

        // 维易/官方均失败时：京东数字 SKU 尝试页面公开主图兜底
        if (p == Platform.JD && itemId.all { it.isDigit() }) {
            return jdImageResolver.resolveMainImage(itemId)
                ?.takeIf { ProductImageUrls.isLoadable(it) }
        }
        return null
    }

    private fun fetchImageBytes(url: String): ByteArray? {
        val normalized = if (url.startsWith("//")) "https:$url" else url
        return runCatching {
            restClient.get()
                .uri(normalized)
                .header("User-Agent", "Mozilla/5.0")
                .header("Referer", "https://m.jd.com/")
                .retrieve()
                .body(ByteArray::class.java)
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    private fun guessMediaType(url: String): MediaType = when {
        url.contains(".png", ignoreCase = true) -> MediaType.IMAGE_PNG
        url.contains(".webp", ignoreCase = true) -> MediaType.parseMediaType("image/webp")
        else -> MediaType.IMAGE_JPEG
    }

    private fun buildPlaceholderPng(title: String): ByteArray {
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
        g.drawString("暂无主图", 145, 210)
        g.color = Color(0x4E, 0x59, 0x69)
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 22)
        val safe = title.take(10)
        g.drawString(safe, (size - g.fontMetrics.stringWidth(safe)) / 2, 320)
        g.dispose()
        return ByteArrayOutputStream().use { out ->
            ImageIO.write(img, "png", out)
            out.toByteArray()
        }
    }
}
