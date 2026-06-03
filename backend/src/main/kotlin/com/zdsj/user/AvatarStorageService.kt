package com.zdsj.user

import com.zdsj.config.AvatarProperties
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration

/**
 * 将微信临时头像 URL 下载到本机目录，返回永久访问路径（/static/avatars/{userId}.ext）。
 * 生产环境由 Nginx 直接托管该目录，避免微信链过期。
 */
@Service
class AvatarStorageService(
    private val props: AvatarProperties,
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
        log.info("头像存储目录: {}", storagePath)
    }

    /**
     * @param sourceUrl 微信临时头像 URL；已是本服务路径则原样返回
     * @return 如 /static/avatars/42.jpg，失败返回 null（不阻塞登录）
     */
    fun persist(userId: Long, sourceUrl: String?): String? {
        if (sourceUrl.isNullOrBlank()) return null
        if (isLocalPath(sourceUrl)) return sourceUrl

        if (!sourceUrl.startsWith("http://") && !sourceUrl.startsWith("https://")) {
            log.warn("跳过头像持久化，非 HTTP URL userId={}", userId)
            return null
        }

        return runCatching {
            val bytes = download(sourceUrl) ?: return null
            persistBytes(userId, bytes)
        }.getOrElse { e ->
            log.warn("头像持久化失败 userId={} url={} err={}", userId, sourceUrl.take(80), e.message)
            null
        }
    }

    /** 小程序 chooseAvatar 上传的二进制内容 */
    fun persistBytes(userId: Long, bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        if (bytes.size > props.maxBytes) {
            log.warn("头像过大 {} bytes", bytes.size)
            return null
        }
        return runCatching {
            val ext = detectExtension(bytes)
            val fileName = "$userId.$ext"
            val target = storagePath.resolve(fileName)
            Files.write(
                target,
                bytes,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            )
            "${props.publicPathPrefix.trimEnd('/')}/$fileName"
        }.getOrElse { e ->
            log.warn("头像写入失败 userId={} err={}", userId, e.message)
            null
        }
    }

    private fun isLocalPath(url: String): Boolean {
        val prefix = props.publicPathPrefix.trimEnd('/')
        return url.startsWith(prefix + "/") || url.startsWith(prefix)
    }

    private fun download(url: String): ByteArray? {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(props.downloadTimeoutSeconds.toLong()))
            .header("User-Agent", "zdsj-backend/1.0")
            .GET()
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
        if (response.statusCode() !in 200..299) {
            log.warn("下载头像 HTTP {} url={}", response.statusCode(), url.take(80))
            return null
        }
        val body = response.body()
        if (body.isEmpty()) return null
        if (body.size > props.maxBytes) {
            log.warn("头像过大 {} bytes，上限 {}", body.size, props.maxBytes)
            return null
        }
        return body
    }

    private fun detectExtension(bytes: ByteArray): String = when {
        bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> "png"
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "jpg"
        bytes.size >= 4 && bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() -> "gif"
        else -> "jpg"
    }
}
