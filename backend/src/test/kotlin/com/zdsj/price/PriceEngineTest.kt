package com.zdsj.price

import com.zdsj.affiliate.AffiliateItem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class PriceEngineTest {

    private val engine = PriceEngine()

    private fun item(platform: String, raw: Int, platformCoupon: Int, subsidy: Int) = AffiliateItem(
        platform = platform,
        platformItemId = "t1",
        title = "Apple iPhone 16 Pro 256GB 国行",
        imageUrl = null,
        shopName = "京东自营",
        shopType = "self",
        rawPrice = BigDecimal(raw),
        couponInfo = mapOf("platformCoupon" to BigDecimal(platformCoupon), "shopCoupon" to BigDecimal(0)),
        subsidyAmount = BigDecimal(subsidy),
        freight = BigDecimal.ZERO,
        activityTags = emptyList(),
        sourceUrl = null,
    )

    @Test
    fun `基础到手价 = 标价 - 平台券 - 补贴`() {
        val r = engine.compute(item("jd", 8999, 200, 100), UserAssets())
        assertEquals(BigDecimal("8699.00"), r.estimatedFinalPrice)
        assertEquals(FinalPriceResult.DISCLAIMER, r.disclaimer)
    }

    @Test
    fun `京东PLUS 会员叠加专属优惠`() {
        val base = engine.compute(item("jd", 8999, 200, 100), UserAssets())
        val withPlus = engine.compute(item("jd", 8999, 200, 100), UserAssets(jdPlus = true))
        assertTrue(withPlus.estimatedFinalPrice < base.estimatedFinalPrice)
        assertTrue(withPlus.included.any { it.name.contains("PLUS") })
    }

    @Test
    fun `国补在配置地区时计入`() {
        val r = engine.compute(item("jd", 8999, 0, 0), UserAssets(govSubsidyRegion = "上海市"))
        assertTrue(r.included.any { it.name.contains("国补") && it.amount > BigDecimal.ZERO })
    }

    @Test
    fun `未纳入优惠包含直播间专属价等`() {
        val r = engine.compute(item("pdd", 8699, 0, 500), UserAssets())
        assertTrue(r.notIncluded.any { it.contains("直播间") })
    }
}
