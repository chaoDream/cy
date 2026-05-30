package com.zdsj.price

import com.zdsj.affiliate.AffiliateItem
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode

/** 用户省钱资产（来自 user_profile.assets_json） */
data class UserAssets(
    val vip88: Boolean = false,
    val jdPlus: Boolean = false,
    val pddMonthly: Boolean = false,
    val govSubsidyRegion: String? = null,
) {
    companion object {
        fun from(assets: Map<String, Any?>?): UserAssets {
            if (assets == null) return UserAssets()
            return UserAssets(
                vip88 = assets["vip88"] as? Boolean ?: false,
                jdPlus = assets["jdPlus"] as? Boolean ?: false,
                pddMonthly = assets["pddMonthly"] as? Boolean ?: false,
                govSubsidyRegion = assets["govSubsidyRegion"] as? String,
            )
        }
    }
}

/** 单条优惠拆解项（瀑布流减法图用） */
data class DiscountItem(val name: String, val amount: BigDecimal, val included: Boolean)

/**
 * 到手价计算结果（PRD §5.3 展示口径）。
 * estimatedFinalPrice 由纯规则计算，绝不经过 AI。
 */
data class FinalPriceResult(
    val displayPrice: BigDecimal,
    val estimatedFinalPrice: BigDecimal,
    val couponAmount: BigDecimal,
    val subsidyAmount: BigDecimal,
    val freight: BigDecimal,
    val included: List<DiscountItem>,      // 已纳入优惠
    val notIncluded: List<String>,         // 未纳入优惠（可能更低）
    val uncertaintyFlags: List<String>,
    val disclaimer: String = DISCLAIMER,
) {
    companion object {
        const val DISCLAIMER = "价格为公开优惠与平台返回数据估算，最终以下单页为准。"
    }
}

/**
 * 个人专属到手价规则引擎（PRD §5.3 / §6）。
 * 公式：标价 − 平台券 − 店铺券 − 跨店满减 − 补贴 −（资产库）会员价/国补 +（运费）
 */
@Service
class PriceEngine {

    fun compute(item: AffiliateItem, assets: UserAssets): FinalPriceResult {
        val display = item.rawPrice
        val included = mutableListOf<DiscountItem>()

        val platformCoupon = bd(item.couponInfo["platformCoupon"])
        val shopCoupon = bd(item.couponInfo["shopCoupon"])
        val crossShop = bd(item.couponInfo["crossShop"])
        val subsidy = item.subsidyAmount

        if (platformCoupon > BigDecimal.ZERO) included += DiscountItem("平台券", platformCoupon, true)
        if (shopCoupon > BigDecimal.ZERO) included += DiscountItem("店铺券", shopCoupon, true)
        if (crossShop > BigDecimal.ZERO) included += DiscountItem("跨店满减", crossShop, true)
        if (subsidy > BigDecimal.ZERO) included += DiscountItem("平台/官方补贴", subsidy, true)

        // 资产库专属优惠（自申报，PRD §5.1）
        val memberDiscount = memberDiscount(item, assets, display)
        if (memberDiscount.amount > BigDecimal.ZERO) included += memberDiscount

        val govSubsidy = govSubsidy(assets, display)
        if (govSubsidy.amount > BigDecimal.ZERO) included += govSubsidy

        val totalCoupon = platformCoupon + shopCoupon + crossShop
        val totalDiscount = totalCoupon + subsidy + memberDiscount.amount + govSubsidy.amount

        val finalPrice = (display - totalDiscount + item.freight)
            .max(BigDecimal.ZERO)
            .setScale(2, RoundingMode.HALF_UP)

        // 暂不精确纳入（PRD §5.3）
        val notIncluded = listOf(
            "个人随机红包", "支付渠道优惠", "直播间专属价", "新客专享价", "以旧换新（可能更低）",
        )

        val uncertaintyFlags = buildList {
            if (assets.vip88 || assets.jdPlus || assets.pddMonthly) add("会员价为自申报估算")
            if (govSubsidy.amount > BigDecimal.ZERO) add("国补以当地政策与名额为准")
        }

        return FinalPriceResult(
            displayPrice = display,
            estimatedFinalPrice = finalPrice,
            couponAmount = totalCoupon,
            subsidyAmount = subsidy + memberDiscount.amount + govSubsidy.amount,
            freight = item.freight,
            included = included,
            notIncluded = notIncluded,
            uncertaintyFlags = uncertaintyFlags,
        )
    }

    private fun memberDiscount(item: AffiliateItem, assets: UserAssets, display: BigDecimal): DiscountItem {
        // 示例规则：会员可叠加约 1% 专属优惠（真实接入后按平台返回的会员价覆盖）
        val rate = when (item.platform) {
            "jd" -> if (assets.jdPlus) BigDecimal("0.01") else BigDecimal.ZERO
            "pdd" -> if (assets.pddMonthly) BigDecimal("0.01") else BigDecimal.ZERO
            else -> if (assets.vip88) BigDecimal("0.01") else BigDecimal.ZERO
        }
        val amount = (display * rate).setScale(2, RoundingMode.HALF_UP)
        val name = when {
            item.platform == "jd" && assets.jdPlus -> "京东PLUS专属"
            item.platform == "pdd" && assets.pddMonthly -> "省钱月卡专属"
            assets.vip88 -> "88VIP专属"
            else -> "会员专属"
        }
        return DiscountItem(name, amount, true)
    }

    private fun govSubsidy(assets: UserAssets, display: BigDecimal): DiscountItem {
        if (assets.govSubsidyRegion.isNullOrBlank()) return DiscountItem("国补", BigDecimal.ZERO, true)
        // 示例：国补常见 15% 封顶 500（实际以地方政策为准）
        val amount = (display * BigDecimal("0.15")).min(BigDecimal("500")).setScale(2, RoundingMode.HALF_UP)
        return DiscountItem("${assets.govSubsidyRegion}国补", amount, true)
    }

    private fun bd(v: Any?): BigDecimal = when (v) {
        is BigDecimal -> v
        is Number -> BigDecimal(v.toString())
        is String -> v.toBigDecimalOrNull() ?: BigDecimal.ZERO
        else -> BigDecimal.ZERO
    }
}
