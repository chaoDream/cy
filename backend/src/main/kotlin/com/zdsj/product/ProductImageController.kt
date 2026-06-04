package com.zdsj.product

import com.zdsj.affiliate.Platform
import com.zdsj.affiliate.provider.AffiliateGateway
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * 商品主图代理：小程序统一走 API 域名加载图片；
 * 有联盟主图则 302 跳转 CDN，否则返回 SVG 占位图。
 */
@RestController
@RequestMapping("/api/product")
class ProductImageController(
    private val rawRepo: ProductRawRepository,
    private val gateway: AffiliateGateway,
) {

    @GetMapping("/image")
    fun image(
        @RequestParam platform: String,
        @RequestParam("item_id") itemId: String,
    ): ResponseEntity<ByteArray> {
        resolveImageUrl(platform, itemId)?.let { url ->
            return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, url)
                .build()
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
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        val p = Platform.fromCode(platform) ?: return null
        return gateway.resolveImage(p, itemId)
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
