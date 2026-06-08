package com.zdsj.affiliate

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class JdGoodsMatcherTest {

    @Test
    fun `picks xiaomi over vivo for xiaomi share title`() {
        val xiaomi = item("10001", "小米17 Pro Max 16+512GB 5G手机 国行", "self")
        val vivo = item("71740439", "vivo X200 Pro 16+512GB 蔡司影像 国行", "self")
        val picked = JdGoodsMatcher.pickBest("小米17ProMAX16+512", listOf(vivo, xiaomi))
        assertEquals("10001", picked?.platformItemId)
    }

    @Test
    fun `tokenizes compact share title`() {
        val tokens = JdGoodsMatcher.tokenize("小米17ProMAX16+512")
        assertTrue(tokens.any { it.contains("小米") })
        assertTrue(tokens.any { it.contains("512") })
    }

    @Test
    fun `rejects iphone when share title is xiaomi`() {
        val iphone = item("jd_iphone16pro", "Apple iPhone 16 Pro 256GB 国行", "self")
        assertTrue(JdGoodsMatcher.matchesShareTitle("小米17ProMAX16+512", item("10001", "小米17 Pro Max 16+512GB", "self")))
        assertEquals(false, JdGoodsMatcher.matchesShareTitle("小米17ProMAX16+512", iphone))
    }

    private fun item(id: String, title: String, shopType: String) = AffiliateItem(
        platform = "jd",
        platformItemId = id,
        title = title,
        imageUrl = null,
        shopName = "旗舰店",
        shopType = shopType,
        rawPrice = BigDecimal("5999"),
        couponInfo = emptyMap(),
        subsidyAmount = BigDecimal.ZERO,
        freight = BigDecimal.ZERO,
        activityTags = emptyList(),
        sourceUrl = null,
    )
}
