package com.zdsj.product

import com.zdsj.config.ProductImageProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.mock
import java.nio.file.Files
import java.nio.file.Path

class ProductImageStorageServiceTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `displayUrl prefers local path when file exists`() {
        val service = service()
        Files.createDirectories(tempDir.resolve("jd"))
        Files.write(tempDir.resolve("jd/1000123.jpg"), byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))
        val raw = ProductRaw(
            platform = "jd",
            platformItemId = "1000123",
            localImagePath = "/static/products/jd/1000123.jpg",
        )
        assertEquals("/static/products/jd/1000123.jpg", service.displayUrl(raw))
    }

    @Test
    fun `resolveDiskPath finds file without db record`() {
        val service = service()
        Files.createDirectories(tempDir.resolve("jd"))
        Files.write(tempDir.resolve("jd/1000123.jpg"), byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))
        assertEquals("/static/products/jd/1000123.jpg", service.resolveDiskPath("jd", "1000123"))
    }

    @Test
    fun `displayUrl falls back to proxy when no local file`() {
        val service = service()
        val raw = ProductRaw(platform = "jd", platformItemId = "1000123")
        val url = service.displayUrl(raw)
        assertTrue(url.startsWith("/api/product/image?platform=jd&item_id="))
    }

    private fun service(): ProductImageStorageService {
        val repo = mock(ProductRawRepository::class.java)
        val service = ProductImageStorageService(
            ProductImageProperties(storageDir = tempDir.toString()),
            repo,
        )
        service.init()
        return service
    }
}
