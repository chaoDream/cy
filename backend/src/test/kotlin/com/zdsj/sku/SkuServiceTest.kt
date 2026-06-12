package com.zdsj.sku

import com.zdsj.affiliate.AffiliateItem
import com.zdsj.affiliate.Platform
import com.zdsj.product.ProductMappingRepository
import com.zdsj.product.ProductSkuRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import java.math.BigDecimal

class SkuServiceTest {

    private val catalogReader = mock(SkuCatalogReader::class.java)
    private val service = SkuService(
        mock(ProductSkuRepository::class.java),
        mock(ProductMappingRepository::class.java),
        catalogReader,
    )
    private val catalog = SkuCatalogSnapshot(
        brandAliasToCanonical = mapOf(
            "一加" to "一加",
            "oneplus" to "一加",
            "apple" to "Apple",
            "iphone" to "Apple",
            "苹果" to "Apple",
        ),
        modelsByBrand = mapOf(
            "一加" to listOf("一加 Ace 6", "Ace 6"),
        ),
    )

    @BeforeEach
    fun setup() {
        `when`(catalogReader.snapshot()).thenReturn(catalog)
    }

    @Test
    fun `uses jd platform brand and catalog model`() {
        val item = jdItem(
            title = "一加 Ace 6 12GB+256GB 快银 oppo 骁龙 8 至尊版",
            platformBrand = "一加",
        )
        val parsed = service.parseItem(item)
        assertEquals("一加", parsed.brand)
        assertEquals("一加 Ace 6", parsed.model)
        assertEquals("high", parsed.confidence)
        assertFalse(parsed.riskTags.contains("暂不在标准型号库"))
    }

    @Test
    fun `falls back to catalog brand alias in title`() {
        val parsed = service.parseTitle("Apple iPhone 16 Pro 256GB 国行")
        assertEquals("Apple", parsed.brand)
        assertEquals("iPhone 16 Pro", parsed.model)
        assertEquals("256GB", parsed.storage)
    }

    @Test
    fun `unknown title stays low confidence`() {
        `when`(catalogReader.snapshot()).thenReturn(SkuCatalogSnapshot.EMPTY)
        val parsed = service.parseTitle("神秘新款旗舰手机 8+128")
        assertTrue(parsed.riskTags.contains("暂不在标准型号库"))
        assertEquals("low", parsed.confidence)
    }

    @Test
    fun `normalize platform brand uses catalog`() {
        assertEquals("一加", service.normalizePlatformBrand("OnePlus一加"))
    }

    private fun jdItem(title: String, platformBrand: String? = null) = AffiliateItem(
        platform = Platform.JD.code,
        platformItemId = "10001",
        title = title,
        imageUrl = null,
        shopName = "京东自营",
        shopType = "self",
        rawPrice = BigDecimal("2999"),
        couponInfo = emptyMap(),
        subsidyAmount = BigDecimal.ZERO,
        freight = BigDecimal.ZERO,
        activityTags = emptyList(),
        sourceUrl = null,
        platformBrand = platformBrand,
    )
}
