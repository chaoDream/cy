package com.zdsj.affiliate.jd

import java.math.BigDecimal

/**
 * 京东联盟价格瀑布（goods.query / 维易 jd_search 同构）：
 *
 *   price（基准原价）
 *     ↓ 叠加秒杀/拼购/会员/平台促销
 *   lowestPrice（活动最低裸价）
 *     ↓ 抵扣最优券（平台自动计算）
 *   lowestCouponPrice（最终到手券后价）
 *
 * 恒有 price ≥ lowestPrice ≥ lowestCouponPrice；无券时后两者相等。
 *
 * 到手价口径：
 *  - 触发特价（lowestPrice < price）：到手价 = lowestPrice − 可叠加券
 *    （单品最大券 + 满足门槛的店铺最大券，平行叠加），标签按 lowestPriceType。
 *  - 未触发：到手价 = lowestCouponPrice，标签「券后价」。
 */
object JdPriceMath {

    /** couponList 单条：quota=门槛，discount=面额，couponType 1=单品券 2=店铺券 */
    data class Coupon(val quota: BigDecimal, val discount: BigDecimal, val couponType: Int)

    data class Result(
        val displayPrice: BigDecimal,     // price（展示价）
        val promoDiscount: BigDecimal,    // price − lowestPrice（活动优惠，常规场景为 0）
        val singleCoupon: BigDecimal,     // 参与扣减的单品券（常规场景为平台净抵扣额）
        val shopCoupon: BigDecimal,       // 参与扣减的店铺券（常规场景为 0）
        val finalPrice: BigDecimal,       // 平台口径到手价（叠加用户资产前）
        val priceTag: String,
        val priceTagType: Int,            // lowestPriceType；常规场景为 0
    ) {
        val couponDeduction: BigDecimal get() = singleCoupon + shopCoupon
    }

    fun compute(
        price: BigDecimal,
        lowestPriceRaw: BigDecimal?,
        lowestPriceType: Int,
        lowestCouponPriceRaw: BigDecimal?,
        officialDiscount: BigDecimal,
        coupons: List<Coupon>,
    ): Result {
        // 数值守恒校验：违反 price ≥ lowestPrice ≥ lowestCouponPrice 的脏数据按上界回落
        val lowest = lowestPriceRaw?.takeIf { it > BigDecimal.ZERO && it <= price } ?: price
        val netted = lowestCouponPriceRaw?.takeIf { it > BigDecimal.ZERO && it <= lowest } ?: lowest

        // 单品券取最大（官方 discount 兜底）；店铺券仅门槛满足时取最大；两类平行叠加
        var maxSingle = officialDiscount.max(BigDecimal.ZERO)
        var bestShop = BigDecimal.ZERO
        for (c in coupons) {
            when (c.couponType) {
                1 -> if (c.discount > maxSingle) maxSingle = c.discount
                2 -> if (price >= c.quota && c.discount > bestShop) bestShop = c.discount
            }
        }

        if (lowest < price) {
            val deduction = (maxSingle + bestShop).min(lowest)
            return Result(
                displayPrice = price,
                promoDiscount = price - lowest,
                singleCoupon = maxSingle.min(deduction),
                shopCoupon = (deduction - maxSingle).max(BigDecimal.ZERO),
                finalPrice = (lowest - deduction).max(BigDecimal.ZERO),
                priceTag = when (lowestPriceType) {
                    3 -> "专享券后价"
                    2 -> "秒杀/拼购价"
                    4 -> "粉丝专享价"
                    else -> "大促优惠价"
                },
                priceTagType = lowestPriceType,
            )
        }
        val deduction = price - netted
        return Result(
            displayPrice = price,
            promoDiscount = BigDecimal.ZERO,
            singleCoupon = deduction,
            shopCoupon = BigDecimal.ZERO,
            finalPrice = netted,
            priceTag = if (deduction > BigDecimal.ZERO) "券后价" else "",
            priceTagType = 0,
        )
    }
}
