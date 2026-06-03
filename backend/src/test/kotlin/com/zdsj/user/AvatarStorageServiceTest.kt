package com.zdsj.user

import com.zdsj.config.AvatarProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class AvatarStorageServiceTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `returns local path unchanged`() {
        val service = AvatarStorageService(AvatarProperties(storageDir = tempDir.toString()))
        service.init()
        assertEquals("/static/avatars/1.jpg", service.persist(1L, "/static/avatars/1.jpg"))
    }

    @Test
    fun `skips blank url`() {
        val service = AvatarStorageService(AvatarProperties(storageDir = tempDir.toString()))
        service.init()
        assertEquals(null, service.persist(1L, null))
        assertEquals(null, service.persist(1L, ""))
    }
}
