package com.zdsj.affiliate.jd

import com.zdsj.config.AffiliateProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito

class JdNumericSkuResolverTest {

    private val resolver = JdNumericSkuResolver(
        veapiClient = Mockito.mock(com.zdsj.affiliate.veapi.VeapiClient::class.java).apply {
            Mockito.`when`(isConfigured()).thenReturn(false)
        },
        jdUnionClient = Mockito.mock(JdUnionClient::class.java),
        props = AffiliateProperties(),
    )

    @Test
    fun `resolve returns numeric id unchanged`() {
        assertEquals("100008656851", resolver.resolve("100008656851"))
    }

    @Test
    fun `candidateUrls prefers hint and normalizes scheme-less jd links`() {
        val urls = resolver.candidateUrls(
            "hash_3abc",
            "jingfen.jd.com/detail/extra.html",
        )
        assertEquals(2, urls.size)
        assertTrue(urls[0].startsWith("https://jingfen.jd.com/detail/extra"))
        assertTrue(urls[1].contains("hash_3abc"))
    }
}
