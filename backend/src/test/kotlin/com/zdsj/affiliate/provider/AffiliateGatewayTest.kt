package com.zdsj.affiliate.provider

import com.zdsj.affiliate.AffiliateItem
import com.zdsj.affiliate.jd.JdNumericSkuResolver
import com.zdsj.affiliate.Platform
import com.zdsj.config.AffiliateProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal

class AffiliateGatewayTest {

    private val cache: AffiliateCache = Mockito.mock(AffiliateCache::class.java)
    private val breaker: ProviderCircuitBreaker = Mockito.mock(ProviderCircuitBreaker::class.java)
    private val metrics: AffiliateMetrics = Mockito.mock(AffiliateMetrics::class.java)

    private fun item(platform: Platform, id: String) = AffiliateItem(
        platform = platform.code,
        platformItemId = id,
        title = "T-$id",
        imageUrl = "https://img/$id.jpg",
        shopName = "shop",
        shopType = "self",
        rawPrice = BigDecimal.TEN,
        couponInfo = emptyMap(),
        subsidyAmount = BigDecimal.ZERO,
        freight = BigDecimal.ZERO,
        activityTags = emptyList(),
        sourceUrl = null,
    )

    /** 可控的假 provider：fetchItem 命中/未命中可配置 */
    private inner class FakeProvider(
        private val n: String,
        private val resultById: (String) -> AffiliateItem?,
        private val supported: Boolean = true,
    ) : AffiliateProvider {
        override fun name() = n
        override fun supports(platform: Platform) = supported
        override fun extractItemId(platform: Platform, linkText: String): String? = null
        override fun fetchItem(ctx: AffiliateContext, itemId: String): AffiliateItem? = resultById(itemId)
        override fun fetchFromShareText(linkText: String, ctx: AffiliateContext?): AffiliateItem? = null
        override fun search(ctx: AffiliateContext, keyword: String, limit: Int): List<AffiliateItem> = emptyList()
        override fun buildCpsLink(ctx: AffiliateContext, itemId: String): String? = null
    }

    private val jdNumericSkuResolver: JdNumericSkuResolver = Mockito.mock(JdNumericSkuResolver::class.java)

    private fun gateway(providers: List<AffiliateProvider>, jdRoute: AffiliateProperties.PlatformRoute): AffiliateGateway {
        val props = AffiliateProperties(provider = AffiliateProperties.ProviderRouting(jd = jdRoute))
        return AffiliateGateway(providers, props, cache, breaker, metrics, jdNumericSkuResolver)
    }

    @Test
    fun `primary success returns primary source without degradation`() {
        val gw = gateway(
            listOf(FakeProvider("veapi", { item(Platform.JD, it) }), MockProvider()),
            AffiliateProperties.PlatformRoute(primary = "veapi", fallback = listOf("mock"), autoFailover = true),
        )
        val r = gw.fetchItem(Platform.JD, "100")
        assertEquals("veapi", r.source)
        assertFalse(r.degraded)
        assertEquals("T-100", r.data?.title)
    }

    @Test
    fun `primary failure falls back to mock and marks degraded`() {
        val gw = gateway(
            listOf(FakeProvider("veapi", { null }), MockProvider()),
            AffiliateProperties.PlatformRoute(primary = "veapi", fallback = listOf("mock"), autoFailover = true),
        )
        val r = gw.fetchItem(Platform.JD, "100")
        assertEquals("mock", r.source)
        assertTrue(r.degraded)
        assertTrue(r.data != null)
        Mockito.verify(breaker).recordFailure("veapi", Platform.JD)
    }

    @Test
    fun `open breaker skips primary under auto failover`() {
        Mockito.`when`(breaker.isOpen("veapi", Platform.JD)).thenReturn(true)
        val gw = gateway(
            listOf(FakeProvider("veapi", { item(Platform.JD, it) }), MockProvider()),
            AffiliateProperties.PlatformRoute(primary = "veapi", fallback = listOf("mock"), autoFailover = true),
        )
        val r = gw.fetchItem(Platform.JD, "100")
        assertEquals("mock", r.source)
        assertTrue(r.degraded)
    }

    @Test
    fun `no auto failover does not use fallback`() {
        val gw = gateway(
            listOf(FakeProvider("veapi", { null }), MockProvider()),
            AffiliateProperties.PlatformRoute(primary = "veapi", fallback = listOf("mock"), autoFailover = false),
        )
        val r = gw.fetchItem(Platform.JD, "100")
        assertNull(r.data)
        assertTrue(r.degraded)
    }

    @Test
    fun `cache hit short circuits providers`() {
        Mockito.`when`(cache.getItem(Platform.JD, "100"))
            .thenReturn(AffiliateCache.CacheHit(item(Platform.JD, "100")))
        val gw = gateway(
            listOf(FakeProvider("veapi", { throw IllegalStateException("should not be called") })),
            AffiliateProperties.PlatformRoute(primary = "veapi", fallback = emptyList(), autoFailover = true),
        )
        val r = gw.fetchItem(Platform.JD, "100")
        assertTrue(r.fromCache)
        assertEquals("cache", r.source)
    }
}
