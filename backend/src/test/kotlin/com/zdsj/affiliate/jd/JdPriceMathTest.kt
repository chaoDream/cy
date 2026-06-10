package com.zdsj.affiliate.jd

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class JdPriceMathTest {

    private fun bd(v: String) = BigDecimal(v)

    @Test
    fun `特价场景 到手价 = 活动裸价 - 单品最大券 - 满足门槛的店铺最大券`() {
        // price 9999 → lowestPrice 9499(type3) ；官方 discount 100；
        // couponList：单品券150、店铺券(满5000减200)、店铺券(满20000减999，门槛不满足)
        val r = JdPriceMath.compute(
            price = bd("9999"),
            lowestPriceRaw = bd("9499"),
            lowestPriceType = 3,
            lowestCouponPriceRaw = bd("9399"),
            officialDiscount = bd("100"),
            coupons = listOf(
                JdPriceMath.Coupon(quota = bd("0"), discount = bd("150"), couponType = 1),
                JdPriceMath.Coupon(quota = bd("5000"), discount = bd("200"), couponType = 2),
                JdPriceMath.Coupon(quota = bd("20000"), discount = bd("999"), couponType = 2),
            ),
        )
        assertEquals(bd("9999"), r.displayPrice)
        assertEquals(bd("500"), r.promoDiscount)        // 9999-9499
        assertEquals(bd("150"), r.singleCoupon)          // max(官方100, 单品150)
        assertEquals(bd("200"), r.shopCoupon)            // 满足门槛的店铺最大
        assertEquals(bd("9149"), r.finalPrice)           // 9499-350
        assertEquals("专享券后价", r.priceTag)
        assertEquals(3, r.priceTagType)
    }

    @Test
    fun `常规场景 到手价 = lowestCouponPrice`() {
        val r = JdPriceMath.compute(
            price = bd("9999"),
            lowestPriceRaw = bd("9999"),               // 无活动
            lowestPriceType = 1,
            lowestCouponPriceRaw = bd("9899"),
            officialDiscount = bd("100"),
            coupons = emptyList(),
        )
        assertEquals(BigDecimal.ZERO, r.promoDiscount)
        assertEquals(bd("100"), r.couponDeduction)       // 9999-9899
        assertEquals(bd("9899"), r.finalPrice)
        assertEquals("券后价", r.priceTag)
    }

    @Test
    fun `无券无活动 三价相等 到手价 = 原价`() {
        val r = JdPriceMath.compute(
            price = bd("9999"),
            lowestPriceRaw = bd("9999"),
            lowestPriceType = 1,
            lowestCouponPriceRaw = bd("9999"),
            officialDiscount = BigDecimal.ZERO,
            coupons = emptyList(),
        )
        assertEquals(bd("9999"), r.finalPrice)
        assertEquals(BigDecimal.ZERO, r.couponDeduction)
        assertEquals("", r.priceTag)
    }

    @Test
    fun `脏数据守恒 lowestPrice 高于 price 时按 price 回落`() {
        val r = JdPriceMath.compute(
            price = bd("9999"),
            lowestPriceRaw = bd("10999"),              // 违反 price>=lowestPrice
            lowestPriceType = 3,
            lowestCouponPriceRaw = bd("12000"),        // 违反 lowestPrice>=lowestCouponPrice
            officialDiscount = BigDecimal.ZERO,
            coupons = emptyList(),
        )
        assertEquals(bd("9999"), r.finalPrice)
        assertEquals(BigDecimal.ZERO, r.promoDiscount)
    }

    @Test
    fun `字段缺失 仅有 price 时到手价 = 原价`() {
        val r = JdPriceMath.compute(
            price = bd("8999"),
            lowestPriceRaw = null,
            lowestPriceType = 0,
            lowestCouponPriceRaw = null,
            officialDiscount = BigDecimal.ZERO,
            coupons = emptyList(),
        )
        assertEquals(bd("8999"), r.finalPrice)
        assertEquals("", r.priceTag)
    }
}
