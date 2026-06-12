package com.zdsj.affiliate.veapi

import com.fasterxml.jackson.databind.JsonNode
import com.zdsj.affiliate.AffiliateItem
import com.zdsj.affiliate.Platform
import com.zdsj.affiliate.jd.JdPriceMath
import com.zdsj.affiliate.jd.JdUnionMetadata
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 维易返回值 → 统一模型 AffiliateItem 的映射 + 校验。
 *
 * 校验机制（补充五）：映射后做必填校验，标题/itemId 缺失视为无效（返回 null 并告警），
 * 价格允许为 0（部分接口返回 -1/缺省，由上层决定是否再补全），避免脏数据进入业务。
 */
@Component
class VeapiMapper {
    private val log = LoggerFactory.getLogger(javaClass)

    private companion object

    /** 京东推广商品主体信息（/jd/promotiongoodsinfo）节点 */
    fun mapJdPromotionGoods(node: JsonNode): AffiliateItem? {
        val skuId = node.path("skuId").asText(null)?.takeIf { it.isNotBlank() }
        val title = node.path("goodsName").asText(null)?.takeIf { it.isNotBlank() }
        val price = node.path("wlUnitPrice").positiveDecimalOrNull()
            ?: node.path("unitPrice").positiveDecimalOrNull()
            ?: BigDecimal.ZERO
        val shopType = if (node.path("isJdSale").asInt(0) == 1) "self" else "thirdparty"
        val meta = JdUnionMetadata.fromGoodsNode(node)
        return build(
            platform = Platform.JD,
            itemId = skuId,
            title = title,
            imageUrl = node.path("imgUrl").asText(null),
            shopName = node.path("shopName").asText(null) ?: "京东",
            shopType = shopType,
            rawPrice = price,
            sourceUrl = node.path("materialUrl").asText(null),
            tags = buildList { if (shopType == "self") add("京东自营"); add("维易·京东联盟") },
            context = "jd_promotiongoodsinfo",
            platformBrand = meta.brandName,
            platformSpuId = meta.spuId,
            platformCategory = meta.category,
        )
    }

    /** 京东搜索（/jd/jd_search）节点；价格瀑布与官方 goods.query 同构，见 JdPriceMath */
    fun mapJdSearchGoods(node: JsonNode): AffiliateItem? {
        val itemId = node.path("skuId").asText(null)?.takeIf { it.isNotBlank() }
            ?: node.path("itemId").asText(null)
        val title = node.path("skuName").asText(null)?.takeIf { it.isNotBlank() }
        val priceInfo = node.path("priceInfo")
        val price = priceInfo.path("price").positiveDecimalOrNull()
            ?: priceInfo.path("lowestPrice").positiveDecimalOrNull()
            ?: priceInfo.path("lowestCouponPrice").positiveDecimalOrNull()
            ?: BigDecimal.ZERO
        val image = node.path("imageInfo").path("imageList").firstUrl()
            ?: node.path("imageInfo").path("whiteImage").asText(null)
        val coupons = node.path("couponList").map {
            JdPriceMath.Coupon(
                quota = it.path("quota").positiveDecimalOrNull() ?: BigDecimal.ZERO,
                discount = it.path("discount").positiveDecimalOrNull() ?: BigDecimal.ZERO,
                couponType = it.path("couponType").asInt(0),
            )
        }
        val pricing = JdPriceMath.compute(
            price = price,
            lowestPriceRaw = priceInfo.path("lowestPrice").positiveDecimalOrNull(),
            lowestPriceType = priceInfo.path("lowestPriceType").asInt(node.path("lowestPriceType").asInt(0)),
            lowestCouponPriceRaw = priceInfo.path("lowestCouponPrice").positiveDecimalOrNull(),
            officialDiscount = priceInfo.path("discount").positiveDecimalOrNull()
                ?: node.path("discount").positiveDecimalOrNull() ?: BigDecimal.ZERO,
            coupons = coupons,
        )
        // 国补促销标签（purchasePriceInfo.promotionLabelInfoList）+ 京东预算到手价
        val govSubsidy = com.zdsj.affiliate.GovSubsidyParser.parse(node)
        val purchasePrice = node.path("purchasePriceInfo").path("purchasePrice").positiveDecimalOrNull()
        val shopType = if (node.path("owner").asText("") == "g") "self" else "thirdparty"
        val meta = JdUnionMetadata.fromGoodsNode(node)
        return build(
            platform = Platform.JD,
            itemId = itemId,
            title = title,
            imageUrl = image,
            shopName = node.path("shopInfo").path("shopName").asText(null),
            shopType = shopType,
            rawPrice = pricing.displayPrice,
            couponInfo = buildMap {
                put("platformCoupon", pricing.singleCoupon)
                put("shopCoupon", pricing.shopCoupon)
                put("promoDiscount", pricing.promoDiscount)
                put("priceTag", pricing.priceTag)
                put("priceTagType", pricing.priceTagType)
                if (purchasePrice != null) put("purchasePrice", purchasePrice)
                govSubsidy?.let { putAll(it) }
            },
            sourceUrl = node.path("materialUrl").asText(null),
            tags = buildList {
                if (shopType == "self") add("京东自营")
                if (pricing.priceTag.isNotBlank()) add(pricing.priceTag)
                if (pricing.couponDeduction > BigDecimal.ZERO) add("券${pricing.couponDeduction.plain()}元")
                govSubsidy?.let { add(com.zdsj.affiliate.GovSubsidyParser.tag(it["govSubsidyType"] as? Int)) }
            },
            context = "jd_search",
            platformBrand = meta.brandName,
            platformSpuId = meta.spuId,
            platformCategory = meta.category,
        )
    }

    /** 拼多多搜索/详情（/pdd/pdd_goodssearch）节点 */
    fun mapPddGoods(node: JsonNode): AffiliateItem? {
        val goodsSign = node.path("goods_sign").asText(null)?.takeIf { it.isNotBlank() }
        val title = node.path("goods_name").asText(null)?.takeIf { it.isNotBlank() }
        val groupPrice = node.path("min_group_price").fenToYuan()
        val normalPrice = node.path("min_normal_price").fenToYuan()
        val price = when {
            groupPrice > BigDecimal.ZERO -> groupPrice
            normalPrice > BigDecimal.ZERO -> normalPrice
            else -> node.path("coupon_min_group_price").fenToYuan()
        }
        val coupon = node.path("coupon_discount").fenToYuan()
        val shopType = when (node.path("merchant_type").asInt(0)) {
            3, 4, 5 -> "flagship"
            else -> "thirdparty"
        }
        return build(
            platform = Platform.PDD,
            itemId = goodsSign,
            title = title,
            imageUrl = node.path("goods_image_url").asText(null)
                ?: node.path("goods_thumbnail_url").asText(null),
            shopName = node.path("mall_name").asText(null),
            shopType = shopType,
            rawPrice = price,
            coupon = coupon,
            sourceUrl = node.path("goods_url").asText(null),
            tags = buildList {
                if (node.path("activity_tags").any { it.asInt() == 7 }) add("百亿补贴")
                if (coupon > BigDecimal.ZERO) add("券${coupon.plain()}元")
                if (isEmpty()) add("多多进宝")
            },
            context = "pdd_goodssearch",
        )
    }

    private fun build(
        platform: Platform,
        itemId: String?,
        title: String?,
        imageUrl: String?,
        shopName: String?,
        shopType: String,
        rawPrice: BigDecimal,
        coupon: BigDecimal = BigDecimal.ZERO,
        couponInfo: Map<String, Any?>? = null,
        sourceUrl: String?,
        tags: List<String>,
        context: String,
        platformBrand: String? = null,
        platformSpuId: String? = null,
        platformCategory: String? = null,
    ): AffiliateItem? {
        if (itemId.isNullOrBlank() || title.isNullOrBlank()) {
            log.warn("[veapi] 映射校验失败 context={} itemId={} hasTitle={}", context, itemId, !title.isNullOrBlank())
            return null
        }
        return AffiliateItem(
            platform = platform.code,
            platformItemId = itemId,
            title = title,
            imageUrl = imageUrl?.let { normalizeImage(it) },
            shopName = shopName,
            shopType = shopType,
            rawPrice = rawPrice,
            couponInfo = couponInfo ?: mapOf("platformCoupon" to coupon, "shopCoupon" to BigDecimal.ZERO),
            subsidyAmount = BigDecimal.ZERO,
            freight = BigDecimal.ZERO,
            activityTags = tags.ifEmpty { listOf("维易") },
            sourceUrl = sourceUrl,
            platformBrand = platformBrand,
            platformSpuId = platformSpuId,
            platformCategory = platformCategory,
        )
    }

    /** provinceNameList 兼容真数组与字符串 "[天津,北京]" 两种返回 */
    private fun JsonNode.asProvinceList(): List<String> = when {
        isMissingNode || isNull -> emptyList()
        isArray -> mapNotNull { it.asText(null)?.trim()?.takeIf(String::isNotBlank) }
        isTextual -> asText().trim().removeSurrounding("[", "]")
            .split(',').map { it.trim().trim('"', '\'', '[', ']') }.filter { it.isNotBlank() }
        else -> emptyList()
    }

    private fun normalizeImage(url: String): String =
        if (url.startsWith("//")) "https:$url" else url

    private fun JsonNode.positiveDecimalOrNull(): BigDecimal? {
        if (isMissingNode || isNull) return null
        val v = asText(null)?.toBigDecimalOrNull() ?: if (isNumber) BigDecimal(asText()) else return null
        return v.takeIf { it > BigDecimal.ZERO }
    }

    private fun JsonNode.fenToYuan(): BigDecimal {
        if (isMissingNode || isNull) return BigDecimal.ZERO
        val fen = asText(null)?.toLongOrNull() ?: if (isNumber) asLong() else return BigDecimal.ZERO
        return BigDecimal(fen).divide(BigDecimal(100), 2, RoundingMode.HALF_UP)
    }

    private fun JsonNode.firstUrl(): String? =
        if (isArray && size() > 0) get(0).path("url").asText(null) else null

    private fun BigDecimal.plain(): String = stripTrailingZeros().toPlainString()
}

private fun JsonNode.firstOrNull(): JsonNode? =
    if (isArray && size() > 0) get(0) else null
