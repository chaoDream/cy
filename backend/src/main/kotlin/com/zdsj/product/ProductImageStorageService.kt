package com.zdsj.product

import com.zdsj.affiliate.ProductImageUrls
import com.zdsj.config.ProductImageProperties
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.time.OffsetDateTime

/**
 * 商品主图按需落盘：解析/盯价/采价写入 product_raw 后，将联盟 CDN 主图缓存到本机。
 * 展示优先走 [localImagePath]（Nginx 静态托管），失败时仍可由 [ProductImageController] 代理外网。
 */
@Service
class ProductImageStorageService(
    private val props: ProductImageProperties,
    private val rawRepo: ProductRawRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val httpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(props.downloadTimeoutSeconds.toLong()))
        .build()

    private lateinit var storagePath: Path

    @PostConstruct
    fun init() {
        storagePath = Path.of(props.storageDir).toAbsolutePath().normalize()
        Files.createDirectories(storagePath)
        log.info("商品主图存储目录: {}", storagePath)
    }

    /** 已有本地文件则跳过；否则尝试下载并更新 DB（失败不抛异常） */
    fun persistIfAbsent(raw: ProductRaw): ProductRaw {
        if (!ProductImageUrls.isLoadable(raw.imageUrl)) return raw
        if (localFileExists(raw.localImagePath)) return raw

        val sourceUrl = raw.imageUrl!!
        val bytes = download(sourceUrl, raw.platform) ?: return raw
        val publicPath = writeBytes(raw.platform, raw.platformItemId, bytes, guessExtension(sourceUrl, bytes))
            ?: return raw
        return saveLocalPathSafe(raw, publicPath)
    }

    /** 异步落盘，避免阻塞商品分析等主链路 */
    @Async
    fun persistIfAbsentAsync(rawId: Long) {
        runCatching {
            val raw = rawRepo.findById(rawId).orElse(null) ?: return
            persistIfAbsent(raw)
        }.onFailure { log.warn("异步商品图落盘失败 rawId={} err={}", rawId, it.message) }
    }

    /** 从指定外链落盘（供图片代理在库内无本地文件时补写） */
    fun persistFromUrl(raw: ProductRaw, sourceUrl: String): ProductRaw {
        if (!ProductImageUrls.isLoadable(sourceUrl)) return raw
        if (localFileExists(raw.localImagePath)) return raw

        val bytes = download(sourceUrl, raw.platform) ?: return raw
        if (!raw.imageUrl.isNullOrBlank() && raw.imageUrl != sourceUrl) {
            raw.imageUrl = sourceUrl
        }
        val publicPath = writeBytes(raw.platform, raw.platformItemId, bytes, guessExtension(sourceUrl, bytes))
            ?: return raw
        return saveLocalPathSafe(raw, publicPath)
    }

    fun displayUrl(raw: ProductRaw): String = displayUrl(raw.platform, raw.platformItemId, raw.localImagePath)

    fun displayUrl(platform: String, itemId: String, localImagePath: String?): String {
        if (localFileExists(localImagePath)) return localImagePath!!
        resolveDiskPath(platform, itemId)?.let { return it }
        return proxyUrl(platform, itemId)
    }

    fun proxyUrl(platform: String, itemId: String): String {
        val encoded = URLEncoder.encode(itemId, StandardCharsets.UTF_8)
        return "/api/product/image?platform=$platform&item_id=$encoded"
    }

    fun localFileExists(localImagePath: String?): Boolean {
        if (localImagePath.isNullOrBlank()) return false
        if (!localImagePath.startsWith(props.publicPathPrefix)) return false
        val relative = localImagePath.removePrefix(props.publicPathPrefix).trimStart('/')
        if (relative.isBlank() || relative.contains("..")) return false
        return Files.isRegularFile(storagePath.resolve(relative))
    }

    fun readLocalBytes(localImagePath: String?): ByteArray? {
        if (!localFileExists(localImagePath)) return null
        val relative = localImagePath!!.removePrefix(props.publicPathPrefix).trimStart('/')
        return readRelativeBytes(relative)
    }

    /** DB 未记录路径时，按 platform+itemId 在磁盘上查找已落盘文件 */
    fun readLocalBytes(platform: String, itemId: String): ByteArray? {
        resolveDiskPath(platform, itemId)?.let { readLocalBytes(it) }?.let { return it }
        return null
    }

    fun resolveDiskPath(platform: String, itemId: String): String? {
        val prefix = props.publicPathPrefix.trimEnd('/')
        for (ext in listOf("jpg", "jpeg", "png", "webp")) {
            val path = "$prefix/${fileRelativePath(platform, itemId, ext)}"
            if (localFileExists(path)) return path
        }
        return null
    }

    fun guessMediaType(localImagePath: String?): String = when {
        localImagePath?.contains(".png", ignoreCase = true) == true -> "image/png"
        localImagePath?.contains(".webp", ignoreCase = true) == true -> "image/webp"
        else -> "image/jpeg"
    }

    fun saveLocalPathSafe(raw: ProductRaw, publicPath: String): ProductRaw =
        runCatching { saveLocalPath(raw, publicPath) }
            .getOrElse {
                log.warn(
                    "商品图路径写库失败 rawId={} path={} err={}（请确认 Flyway V6 已执行）",
                    raw.id,
                    publicPath,
                    it.message,
                )
                raw.localImagePath = publicPath
                raw
            }

    @Transactional
    fun saveLocalPath(raw: ProductRaw, publicPath: String): ProductRaw {
        val managed = raw.id?.let { rawRepo.findById(it).orElse(raw) } ?: raw
        managed.localImagePath = publicPath
        managed.imageStoredAt = OffsetDateTime.now()
        return rawRepo.save(managed)
    }

    private fun readRelativeBytes(relative: String): ByteArray? =
        runCatching { Files.readAllBytes(storagePath.resolve(relative)) }
            .getOrElse {
                log.warn("读取本地商品图失败 relative={} err={}", relative, it.message)
                null
            }

    private fun writeBytes(platform: String, itemId: String, bytes: ByteArray, ext: String): String? {
        if (bytes.isEmpty()) return null
        if (bytes.size > props.maxBytes) {
            log.warn("商品图过大 {} bytes platform={} itemId={}", bytes.size, platform, itemId.take(32))
            return null
        }
        return runCatching {
            val relative = fileRelativePath(platform, itemId, ext)
            val target = storagePath.resolve(relative)
            Files.createDirectories(target.parent)
            Files.write(
                target,
                bytes,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            )
            "${props.publicPathPrefix.trimEnd('/')}/$relative"
        }.getOrElse {
            log.warn("商品图写入失败 platform={} itemId={} err={}", platform, itemId.take(32), it.message)
            null
        }
    }

    private fun fileRelativePath(platform: String, itemId: String, ext: String): String {
        val safePlatform = platform.lowercase().replace(Regex("[^a-z0-9]"), "")
        val safeId = itemId.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(120)
        val name = if (safeId.isNotBlank()) safeId else itemId.hashCode().toString(16)
        return "$safePlatform/$name.$ext"
    }

    private fun download(url: String, platform: String): ByteArray? {
        val normalized = if (url.startsWith("//")) "https:$url" else url
        return runCatching {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(normalized))
                .timeout(Duration.ofSeconds(props.downloadTimeoutSeconds.toLong()))
                .header("User-Agent", "Mozilla/5.0 (compatible; zdsj-backend/1.0)")
                .header("Referer", refererFor(platform))
                .GET()
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
            if (response.statusCode() !in 200..299) {
                log.warn("下载商品图 HTTP {} url={}", response.statusCode(), normalized.take(80))
                return null
            }
            val body = response.body()
            if (body.isEmpty()) return null
            if (body.size > props.maxBytes) {
                log.warn("商品图过大 {} bytes", body.size)
                return null
            }
            body
        }.getOrElse {
            log.warn("下载商品图失败 url={} err={}", normalized.take(80), it.message)
            null
        }
    }

    private fun refererFor(platform: String): String = when (platform.lowercase()) {
        "pdd" -> "https://mobile.yangkeduo.com/"
        else -> "https://m.jd.com/"
    }

    private fun guessExtension(url: String, bytes: ByteArray): String = when {
        url.contains(".png", ignoreCase = true) -> "png"
        url.contains(".webp", ignoreCase = true) -> "webp"
        bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> "png"
        bytes.size >= 12 && bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() -> "webp"
        else -> "jpg"
    }
}
