package com.zdsj.price

import com.zdsj.affiliate.AffiliateItem
import com.zdsj.config.GovSubsidyProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class PriceEngineTest {

    private val engine = PriceEngine()

    // 国补走配置表：构造一个含「上海市」规则的引擎
    private val engineWithSubsidy = PriceEngine(
        GovSubsidyProperties(
            enabled = true,
            regions = listOf(
                GovSubsidyProperties.Region(
                    region = "上海市", rate = BigDecimal("0.15"), cap = BigDecimal("2000"),
                ),
            ),
        ),
    )

    private fun item(
        platform: String,
        raw: Int,
        platformCoupon: Int,
        subsidy: Int,
        memberPrice: Int? = null,
        memberType: String? = null,
    ) = AffiliateItem(
        platform = platform,
        platformItemId = "t1",
        title = "Apple iPhone 16 Pro 256GB 国行",
        imageUrl = null,
        shopName = "京东自营",
        shopType = "self",
        rawPrice = BigDecimal(raw),
        couponInfo = buildMap<String, Any?> {
            put("platformCoupon", BigDecimal(platformCoupon))
            put("shopCoupon", BigDecimal(0))
            if (memberPrice != null) put("memberPrice", BigDecimal(memberPrice))
            if (memberType != null) put("memberType", memberType)
        },
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
    fun `京东PLUS 会员价计入专属优惠`() {
        // 联盟返回 PLUS 会员价 8800：优惠额 = 8999 - 8800 = 199
        val withMemberPrice = item("jd", 8999, 200, 100, memberPrice = 8800, memberType = "jdPlus")
        val base = engine.compute(withMemberPrice, UserAssets())
        val withPlus = engine.compute(withMemberPrice, UserAssets(jdPlus = true))
        assertTrue(withPlus.estimatedFinalPrice < base.estimatedFinalPrice)
        val plus = withPlus.included.firstOrNull { it.name.contains("PLUS") }
        assertTrue(plus != null && plus.amount == BigDecimal("199.00"))
    }

    @Test
    fun `无会员价数据时不计会员优惠`() {
        // 接口未返回 memberPrice：即使用户是 PLUS 也不估算
        val r = engine.compute(item("jd", 8999, 200, 100), UserAssets(jdPlus = true))
        assertTrue(r.included.none { it.name.contains("PLUS") && it.amount > BigDecimal.ZERO })
    }

    @Test
    fun `国补按配置地区规则计入`() {
        val r = engineWithSubsidy.compute(item("jd", 8999, 0, 0), UserAssets(govSubsidyRegion = "上海市"))
        // 8999 * 0.15 = 1349.85，未触顶 2000
        val sub = r.included.firstOrNull { it.name.contains("国补") }
        assertTrue(sub != null && sub.amount == BigDecimal("1349.85"))
    }

    @Test
    fun `未配置地区不计国补`() {
        val r = engineWithSubsidy.compute(item("jd", 8999, 0, 0), UserAssets(govSubsidyRegion = "未知省"))
        assertTrue(r.included.none { it.name.contains("国补") && it.amount > BigDecimal.ZERO })
    }

    @Test
    fun `京东特价场景 活动优惠与券分项扣减不重复`() {
        // price 9999；活动优惠500(type3)；单品券100 + 店铺券200 → 到手 9199
        val special = item("jd", 9999, 100, 0).copy(
            couponInfo = mapOf(
                "platformCoupon" to BigDecimal(100),
                "shopCoupon" to BigDecimal(200),
                "promoDiscount" to BigDecimal(500),
                "priceTagType" to 3,
            ),
        )
        val r = engine.compute(special, UserAssets())
        assertEquals(BigDecimal("9199.00"), r.estimatedFinalPrice)
        assertTrue(r.included.any { it.name.contains("PLUS专享") && it.amount == BigDecimal(500) })
        // 非 PLUS 用户：提示专享价有条件
        assertTrue(r.uncertaintyFlags.any { it.contains("PLUS") })
        // PLUS 用户：不再提示
        val plus = engine.compute(special, UserAssets(jdPlus = true))
        assertTrue(plus.uncertaintyFlags.none { it.contains("PLUS") })
    }

    @Test
    fun `未纳入优惠包含直播间专属价等`() {
        val r = engine.compute(item("pdd", 8699, 0, 500), UserAssets())
        assertTrue(r.notIncluded.any { it.contains("直播间") })
    }
}
