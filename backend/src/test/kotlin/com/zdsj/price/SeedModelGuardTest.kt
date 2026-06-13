package com.zdsj.price

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import com.zdsj.affiliate.AffiliateItem
import com.zdsj.affiliate.Platform
import java.math.BigDecimal

class SeedModelGuardTest {

    private fun item(title: String) = AffiliateItem(
        platform = Platform.JD.code,
        platformItemId = "100001",
        title = title,
        imageUrl = null,
        shopName = "京东自营",
        shopType = "self",
        rawPrice = BigDecimal("3999"),
        couponInfo = emptyMap(),
        subsidyAmount = BigDecimal.ZERO,
        freight = BigDecimal.ZERO,
        activityTags = emptyList(),
        sourceUrl = null,
    )

    @Test
    fun `rejects iphone generation drift`() {
        assertFalse(
            SeedModelGuard.matchesSeed(
                "iPhone 16 Pro Max 256GB 国行",
                item("Apple iPhone 17 Pro Max 256GB 国行"),
            ),
        )
        assertTrue(
            SeedModelGuard.matchesSeed(
                "iPhone 16 Pro Max 256GB 国行",
                item("Apple iPhone 16 Pro Max 256GB 国行 5G"),
            ),
        )
    }

    @Test
    fun `rejects x series drift`() {
        assertFalse(
            SeedModelGuard.matchesSeed(
                "vivo X100 Pro 12GB+256GB 国行",
                item("vivo X200 Pro 12GB+256GB 国行"),
            ),
        )
    }

    @Test
    fun `rejects phone seed matched to laptop`() {
        assertFalse(
            SeedModelGuard.matchesSeed(
                "iPhone 15 Pro 256GB 国行",
                item("Apple MacBook Pro 15 英寸 M3 笔记本"),
            ),
        )
    }
}
