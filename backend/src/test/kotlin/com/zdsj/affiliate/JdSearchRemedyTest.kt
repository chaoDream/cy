package com.zdsj.affiliate

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class JdSearchRemedyTest {

    @Test
    fun `brandModelKeyword extracts vivo S60 from marketing title`() {
        assertEquals(
            "vivo S60",
            JdSearchRemedy.brandModelKeyword("vivo S60 长续航AI拍照手机"),
        )
    }

    @Test
    fun `recallKeywords includes degrade brand model and sku`() {
        val keys = JdSearchRemedy.recallKeywords("vivo S60 12GB+256GB 自营", "100272975491")
        assertTrue(keys.contains("vivo S60 12GB+256GB 自营"))
        assertTrue(keys.any { it.contains("vivo") && it.contains("S60", ignoreCase = true) })
        assertTrue(keys.contains("100272975491"))
    }

    @Test
    fun `pickPricedMatch rejects wrong series`() {
        val s20 = item("s20", "vivo S20 6500mAh长续航 AI手机", 2799)
        val s60 = item("s60", "vivo S60 12GB+256GB AI拍照手机", 3599)
        val picked = JdSearchRemedy.pickPricedMatch("vivo S60 长续航AI拍照手机", listOf(s20, s60))
        assertNotNull(picked)
        assertEquals("s60", picked!!.platformItemId)
    }

    @Test
    fun `pickPricedBySku prefers matching sku`() {
        val hits = listOf(
            item("mat1", "vivo S60 12GB", 3599),
            item("100272975491", "vivo S60 长续航", 3299),
        )
        val picked = JdSearchRemedy.pickPricedBySku(hits, "100272975491")
        assertEquals("100272975491", picked?.platformItemId)
    }

    @Test
    fun `brandModelKeyword returns null without brand`() {
        assertNull(JdSearchRemedy.brandModelKeyword("长续航AI拍照手机"))
    }

    private fun item(id: String, title: String, price: Int) = AffiliateItem(
        platform = Platform.JD.code,
        platformItemId = id,
        title = title,
        imageUrl = null,
        shopName = "京东",
        shopType = "self",
        rawPrice = BigDecimal(price),
        couponInfo = emptyMap(),
        subsidyAmount = BigDecimal.ZERO,
        freight = BigDecimal.ZERO,
        activityTags = emptyList(),
        sourceUrl = null,
    )
}
