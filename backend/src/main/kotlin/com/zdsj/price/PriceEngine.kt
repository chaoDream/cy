package com.zdsj.price

import com.zdsj.affiliate.AffiliateItem
import com.zdsj.config.GovSubsidyProperties
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
    val pricePending: Boolean,
    val displayPrice: BigDecimal?,
    val estimatedFinalPrice: BigDecimal?,
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
class PriceEngine(
    private val subsidyProps: GovSubsidyProperties = GovSubsidyProperties(),
) {

    private companion object {
        /** 省份名归一化去除的后缀（长复合在前，确保单遍即可剥净） */
        val PROVINCE_SUFFIXES = listOf(
            "维吾尔自治区", "壮族自治区", "回族自治区", "特别行政区", "自治区", "省", "市",
        )
    }

    fun compute(item: AffiliateItem, assets: UserAssets): FinalPriceResult {
        val display = item.rawPrice
        if (display <= BigDecimal.ZERO) {
            return FinalPriceResult(
                pricePending = true,
                displayPrice = null,
                estimatedFinalPrice = null,
                couponAmount = BigDecimal.ZERO,
                subsidyAmount = BigDecimal.ZERO,
                freight = BigDecimal.ZERO,
                included = emptyList(),
                notIncluded = emptyList(),
                uncertaintyFlags = emptyList(),
                disclaimer = "联盟暂未返回价格，请重新粘贴链接或稍后重试",
            )
        }

        val included = mutableListOf<DiscountItem>()

        val platformCoupon = bd(item.couponInfo["platformCoupon"])
        val shopCoupon = bd(item.couponInfo["shopCoupon"])
        val crossShop = bd(item.couponInfo["crossShop"])
        val subsidy = item.subsidyAmount
        val promoDiscount = bd(item.couponInfo["promoDiscount"])
        val promoType = (item.couponInfo["priceTagType"] as? Number)?.toInt() ?: 0

        if (promoDiscount > BigDecimal.ZERO) included += DiscountItem(promoName(promoType), promoDiscount, true)
        if (platformCoupon > BigDecimal.ZERO) included += DiscountItem("平台券", platformCoupon, true)
        if (shopCoupon > BigDecimal.ZERO) included += DiscountItem("店铺券", shopCoupon, true)
        if (crossShop > BigDecimal.ZERO) included += DiscountItem("跨店满减", crossShop, true)
        if (subsidy > BigDecimal.ZERO) included += DiscountItem("平台/官方补贴", subsidy, true)

        val memberDiscount = memberDiscount(item, assets, display)
        if (memberDiscount.amount > BigDecimal.ZERO) included += memberDiscount

        val totalCoupon = platformCoupon + shopCoupon + crossShop
        val govBase = (display - totalCoupon - promoDiscount - subsidy - memberDiscount.amount)
            .max(BigDecimal.ZERO)
        val govSubsidy = govSubsidy(item, assets, govBase)
        if (govSubsidy.amount > BigDecimal.ZERO) included += govSubsidy

        val totalDiscount = totalCoupon + promoDiscount + subsidy + memberDiscount.amount + govSubsidy.amount

        val finalPrice = (display - totalDiscount + item.freight)
            .max(BigDecimal.ZERO)
            .setScale(2, RoundingMode.HALF_UP)

        val notIncluded = listOf(
            "个人随机红包", "支付渠道优惠", "直播间专属价", "新客专享价", "以旧换新（可能更低）",
        )

        val uncertaintyFlags = buildList {
            if (promoDiscount > BigDecimal.ZERO && promoType == 3 && !assets.jdPlus) {
                add("专享价通常需 PLUS 会员，非会员到手价可能略高")
            }
            if (promoDiscount > BigDecimal.ZERO && promoType == 2) add("秒杀/拼购价需抢购或成团")
            if (memberDiscount.amount > BigDecimal.ZERO) add("会员价以平台实际核验为准")
            if (govSubsidy.amount > BigDecimal.ZERO) add("国补以当地政策与名额为准")
        }

        return FinalPriceResult(
            pricePending = false,
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

    /** 活动优惠明细名称（lowestPriceType：3 专享/PLUS、2 秒杀拼购、4 粉丝/预售） */
    private fun promoName(type: Int): String = when (type) {
        3 -> "PLUS专享优惠"
        2 -> "秒杀/拼购优惠"
        4 -> "粉丝专享优惠"
        else -> "大促活动优惠"
    }

    /**
     * 会员专属优惠：用联盟接口返回的真实会员价（couponInfo.memberPrice）计算，
     * 而非估算。优惠额 = 标价 − 会员价，仅当用户持有对应会员（memberType 匹配）时计入。
     * 接口未返回会员价时该项为 0、不展示。
     */
    private fun memberDiscount(item: AffiliateItem, assets: UserAssets, display: BigDecimal): DiscountItem {
        val memberType = item.couponInfo["memberType"] as? String
        val memberPrice = bd(item.couponInfo["memberPrice"])
        val userHasMembership = when (memberType) {
            "jdPlus" -> assets.jdPlus
            "pddMonthly" -> assets.pddMonthly
            "vip88" -> assets.vip88
            else -> false
        }
        val name = when (memberType) {
            "jdPlus" -> "京东PLUS专属"
            "pddMonthly" -> "省钱月卡专属"
            "vip88" -> "88VIP专属"
            else -> "会员专属"
        }
        if (!userHasMembership || memberPrice <= BigDecimal.ZERO || memberPrice >= display) {
            return DiscountItem(name, BigDecimal.ZERO, true)
        }
        val amount = (display - memberPrice).setScale(2, RoundingMode.HALF_UP)
        return DiscountItem(name, amount, true)
    }

    /**
     * 政府国补：优先用联盟接口返回的国补标签（couponInfo.govSubsidy*，比例/封顶/生效省份），
     * 接口未返回时回落 zdsj.subsidy 地区规则表（由运营维护）。
     * 优惠额 = min(基数 × 比例, 封顶)，基数为扣券后净价；用户省份不在生效范围或未配置时为 0。
     */
    private fun govSubsidy(item: AffiliateItem, assets: UserAssets, base: BigDecimal): DiscountItem {
        val region = assets.govSubsidyRegion
        if (region.isNullOrBlank() || base <= BigDecimal.ZERO) {
            return DiscountItem(region?.let { "${it}国补" } ?: "国补", BigDecimal.ZERO, true)
        }

        // 平台数据优先：联盟返回 rebate/topDiscount/生效省份
        val platformRate = bd(item.couponInfo["govSubsidyRate"])
        if (platformRate > BigDecimal.ZERO) {
            @Suppress("UNCHECKED_CAST")
            val provinces = (item.couponInfo["govSubsidyProvinces"] as? List<String>).orEmpty()
            if (provinces.none { sameProvince(it, region) }) {
                return DiscountItem("${region}国补", BigDecimal.ZERO, true)
            }
            var amount = base * platformRate
            val cap = bd(item.couponInfo["govSubsidyCap"])
            if (cap > BigDecimal.ZERO) amount = amount.min(cap)
            return DiscountItem("${region}国补", amount.setScale(2, RoundingMode.HALF_UP), true)
        }

        // 回落配置表
        val rule = subsidyProps.match(region)
            ?: return DiscountItem("${region}国补", BigDecimal.ZERO, true)
        if (rule.minPrice > BigDecimal.ZERO && base < rule.minPrice) {
            return DiscountItem("${rule.region}国补", BigDecimal.ZERO, true)
        }
        var amount = base * rule.rate
        if (rule.cap > BigDecimal.ZERO) amount = amount.min(rule.cap)
        return DiscountItem("${rule.region}国补", amount.setScale(2, RoundingMode.HALF_UP), true)
    }

    /** 省份名归一化比较：接口返回短名（天津），资产库存带后缀（天津市），去后缀后比较 */
    private fun sameProvince(a: String, b: String): Boolean =
        normalizeProvince(a) == normalizeProvince(b) && normalizeProvince(a).isNotBlank()

    private fun normalizeProvince(s: String): String {
        var r = s.trim()
        for (suffix in PROVINCE_SUFFIXES) r = r.removeSuffix(suffix)
        return r
    }

    private fun bd(v: Any?): BigDecimal = when (v) {
        is BigDecimal -> v
        is Number -> BigDecimal(v.toString())
        is String -> v.toBigDecimalOrNull() ?: BigDecimal.ZERO
        else -> BigDecimal.ZERO
    }
}
