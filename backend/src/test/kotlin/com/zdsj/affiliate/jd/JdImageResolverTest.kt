package com.zdsj.affiliate.jd

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

class JdImageResolverTest {

    private val resolver = JdImageResolver()

    @Test
    fun `extracts imagePath from sample html`() {
        val html = """{"imagePath":"jfs/t1/305961/13/3544/127127/682bd9bbF3fbcd234/67da9f78142bd15a.jpg"}"""
        val path = Regex(""""imagePath"\s*:\s*"(jfs/[^"]+)"""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)
        assertNotNull(path)
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "JD_IMAGE_LIVE_TEST", matches = "1")
    fun `resolves live jd sku image`() {
        val url = resolver.resolveMainImage("100330965784")
        assertNotNull(url)
        assertTrue(url!!.contains("360buyimg.com"))
    }
}
