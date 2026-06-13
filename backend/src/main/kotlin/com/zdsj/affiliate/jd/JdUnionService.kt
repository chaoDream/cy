package com.zdsj.affiliate.jd

import com.fasterxml.jackson.databind.JsonNode
import com.zdsj.affiliate.ActivityTags
import com.zdsj.affiliate.AffiliateItem
import com.zdsj.affiliate.JdGoodsMatcher
import com.zdsj.affiliate.JdLinkParser
import com.zdsj.affiliate.Platform
import com.zdsj.common.BizException
import com.zdsj.common.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal

@Service
class JdUnionService(
    private val client: JdUnionClient,
    private val imageResolver: JdImageResolver,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 从分享文案解析并拉取真实商品（短链 → 转链 → SKU → 详情） */
    fun fetchFromShareText(linkText: String): AffiliateItem {
        val skuId = resolveSkuId(linkText)
            ?: throw BizException(ErrorCode.PARSE_FAILED, "暂时没识别出来，可以换一个链接试试")
        return fetchBySkuId(skuId, linkText)
    }

    fun fetchBySkuId(skuId: String, sourceLink: String? = null, fallbackTitle: String? = null): AffiliateItem {
        queryGoodsDetail(skuId)?.let { return it.withImageFallback(skuId) }
        queryPromotionInfo(skuId)?.let { return it.withImageFallback(skuId) }

        // 联盟商品查询无权限时：分享标题 / 库内标题 + 移动端页抓主图（价格仍依赖联盟接口）
        val title = JdLinkParser.extractShareTitle(sourceLink.orEmpty())
            ?: fallbackTitle?.takeIf { it.isNotBlank() }
        val imageUrl = imageResolver.resolveMainImage(skuId)
        if (title != null || imageUrl != null) {
            log.info(
                "京东联盟商品详情无权限，降级 skuId={} title={} hasImage={}",
                skuId,
                title?.take(20),
                imageUrl != null,
            )
            return AffiliateItem(
                platform = Platform.JD.code,
                platformItemId = skuId,
                title = title ?: "京东商品",
                imageUrl = imageUrl,
                shopName = "京东",
                shopType = "self",
                rawPrice = BigDecimal.ZERO,
                couponInfo = emptyMap(),
                subsidyAmount = BigDecimal.ZERO,
                freight = BigDecimal.ZERO,
                activityTags = emptyList(),
                sourceUrl = JdLinkParser.extractUrl(sourceLink.orEmpty()),
            )
        }

        throw BizException(ErrorCode.PARSE_FAILED, "商品已下架或不在推广计划中")
    }

    fun search(keyword: String, limit: Int): List<AffiliateItem> {
        val data = client.searchGoods(keyword, limit) ?: return emptyList()
        return extractGoodsList(data).mapNotNull { mapGoodsNode(it, keyword) }
    }

    fun jingfen(eliteId: Int, pageSize: Int): List<AffiliateItem> {
        val data = client.queryJingfen(eliteId, pageSize) ?: return emptyList()
        return extractGoodsList(data).mapNotNull { mapGoodsNode(it, "") }
    }

    /** 千人千面物料推荐（猜你喜欢 / 实时热销等，eliteId 与 jingfen 不同） */
    fun materialRecommend(eliteId: Int, pageSize: Int, userKey: String? = null): List<AffiliateItem> {
        val (userIdType, userId) = pseudoDeviceId(userKey)
        val data = client.queryMaterialRecommend(eliteId, pageSize, userIdType, userId) ?: return emptyList()
        return extractGoodsList(data).mapNotNull { mapGoodsNode(it, "") }
    }

    private fun pseudoDeviceId(userKey: String?): Pair<Int?, String?> {
        val key = userKey?.takeIf { it.isNotBlank() } ?: return null to null
        // 小程序无 idfa/imei，用 userKey 的 MD5 大写模拟 128 类型，提升千人千面命中率
        val md5 = java.security.MessageDigest.getInstance("MD5")
            .digest(key.toByteArray())
            .joinToString("") { "%02X".format(it) }
        return 128 to md5
    }

    /**
     * 单品推广转链。京东规则：sceneId=1 仅支持 jingfen 联盟链；item.jd.com 须 sceneId=2（需联盟权限）。
     * sceneId=1 + item.jd.com 会落到活动推广页（如「又好又便宜」），非商品详情。
     */
    fun buildCpsLink(skuId: String): String? {
        if (!skuId.all { it.isDigit() }) {
            return convertLinkToPromoUrl(skuId, sceneId = null)
        }
        val attempts = listOf(
            "https://item.jd.com/$skuId.html" to 2,
            "https://jingfen.jd.com/detail/$skuId.html" to 1,
            skuId to 2,
        )
        for ((material, sceneId) in attempts) {
            convertLinkToPromoUrl(material, sceneId)?.let { return it }
        }
        return null
    }

    private fun convertLinkToPromoUrl(material: String, sceneId: Int?): String? {
        val data = client.convertLink(material, sceneId) ?: return null
        return pickPromoUrl(data, material)
    }

    private fun pickPromoUrl(data: JsonNode, material: String): String? {
        val skuFromApi = data.path("skuId").asText(null)?.takeIf { it.all(Char::isDigit) }
        val expectedSku = JdLinkParser.extractItemIdFromUrl(material)
            ?: material.takeIf { it.all(Char::isDigit) }
        if (skuFromApi != null && expectedSku != null && skuFromApi != expectedSku) return null

        val click = data.path("clickURL").asText(null)
        val short = data.path("shortURL").asText(null)
        if (click != null) {
            if (expectedSku == null || clickContainsSku(click, expectedSku) || skuFromApi == expectedSku) {
                return click
            }
        }
        if (skuFromApi != null && expectedSku != null && skuFromApi == expectedSku) {
            return short ?: click
        }
        return null
    }

    private fun clickContainsSku(clickUrl: String, skuId: String): Boolean =
        clickUrl.contains(skuId) ||
            clickUrl.contains("item.jd.com/$skuId") ||
            clickUrl.contains("item.m.jd.com/product/$skuId")

    private fun resolveSkuId(linkText: String): String? {
        val direct = JdLinkParser.extractItemId(linkText)
        if (direct != null && direct.all { it.isDigit() }) return direct

        val url = JdLinkParser.extractUrl(linkText) ?: return null

        JdLinkParser.extractItemIdFromUrl(url)?.let { return it }
        client.resolveSkuIdFromUrl(url)?.let { return it }

        val converted = runCatching { client.convertLink(url) }.getOrNull()
        converted?.path("skuId")?.asText(null)?.let { return it }
        val clickUrl = converted?.path("clickURL")?.asText(null)
        if (clickUrl != null) {
            client.resolveSkuIdFromUrl(clickUrl)?.let { return it }
        }

        JdLinkParser.extractShareTitle(linkText)?.let { title ->
            val results = search(title, 8)
            JdGoodsMatcher.pickBest(title, results)?.platformItemId?.let { return it }
        }
        return null
    }

    private fun queryGoodsDetail(skuId: String): AffiliateItem? {
        val data = runCatching { client.queryGoods(skuIds = listOf(skuId), pageSize = 1) }.getOrNull()
            ?: return null
        val node = extractGoodsList(data).firstOrNull() ?: return null
        return mapGoodsNode(node, skuId)
    }

    private fun queryPromotionInfo(skuId: String): AffiliateItem? {
        val data = client.queryPromotionGoodsInfo(listOf(skuId)) ?: return null
        val node = extractPromotionList(data).firstOrNull() ?: return null
        return mapPromotionNode(node, skuId)
    }

    /** 批量推广信息查价（官方 promotiongoodsinfo，最多 100 SKU/次） */
    fun fetchPromotionBatch(skuIds: List<String>): List<AffiliateItem> {
        if (skuIds.isEmpty()) return emptyList()
        return skuIds.distinct().filter { it.all(Char::isDigit) }.chunked(50).flatMap { chunk ->
            val data = client.queryPromotionGoodsInfo(chunk) ?: return@flatMap emptyList()
            extractPromotionList(data).mapNotNull { node ->
                val sku = node.path("skuId").asText(null)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                mapPromotionNode(node, sku)
            }
        }
    }

    private fun extractPromotionList(data: JsonNode): List<JsonNode> {
        if (data.isArray) return data.toList()
        val nested = data.path("data")
        if (nested.isArray) return nested.toList()
        return emptyList()
    }

    private fun extractGoodsList(data: JsonNode): List<JsonNode> {
        if (data.isArray) return data.toList()
        val list = data.path("goodsList")
        if (list.isArray) return list.toList()
        return emptyList()
    }

    private fun mapGoodsNode(node: JsonNode, fallbackSku: String): AffiliateItem? {
        val skuId = node.path("skuId").asText(fallbackSku)
        val title = node.path("skuName").asText(null) ?: node.path("goodsName").asText(null)
        if (title.isNullOrBlank()) return null

        val priceInfo = node.path("priceInfo")
        val price = priceInfo.path("price").decimalOrNull()
            ?: node.path("lowestPrice").decimalOrNull()
            ?: node.path("price").decimalOrNull()
            ?: BigDecimal.ZERO

        // 价格瀑布：price ≥ lowestPrice（活动裸价）≥ lowestCouponPrice（券后价），见 JdPriceMath
        val coupons = node.path("couponInfo").path("couponList").map {
            JdPriceMath.Coupon(
                quota = it.path("quota").decimalOrNull() ?: BigDecimal.ZERO,
                discount = it.path("discount").decimalOrNull() ?: BigDecimal.ZERO,
                couponType = it.path("couponType").asInt(0),
            )
        }
        val pricing = JdPriceMath.compute(
            price = price,
            lowestPriceRaw = priceInfo.path("lowestPrice").decimalOrNull(),
            lowestPriceType = priceInfo.path("lowestPriceType").asInt(node.path("lowestPriceType").asInt(0)),
            lowestCouponPriceRaw = priceInfo.path("lowestCouponPrice").decimalOrNull(),
            officialDiscount = priceInfo.path("discount").decimalOrNull()
                ?: node.path("discount").decimalOrNull() ?: BigDecimal.ZERO,
            coupons = coupons,
        )

        val shopType = when (node.path("owner").asText("")) {
            "g" -> "self"
            else -> "thirdparty"
        }
        val meta = JdUnionMetadata.fromGoodsNode(node)
        val govSubsidy = com.zdsj.affiliate.GovSubsidyParser.parse(node)
        val tags = buildList {
            if (shopType == "self") add("京东自营")
            if (pricing.priceTag.isNotBlank()) add(pricing.priceTag)
            if (pricing.couponDeduction > BigDecimal.ZERO) {
                add("券${pricing.couponDeduction.stripTrailingZeros().toPlainString()}元")
            }
            govSubsidy?.let { add(com.zdsj.affiliate.GovSubsidyParser.tag(it["govSubsidyType"] as? Int)) }
            node.path("skuTagList").forEach { add(it.asText()) }
        }

        return AffiliateItem(
            platform = Platform.JD.code,
            platformItemId = skuId,
            title = title,
            imageUrl = node.path("imageInfo").path("imageList").firstOrNull()?.path("url")?.asText(null),
            shopName = node.path("shopInfo").path("shopName").asText(null),
            shopType = shopType,
            rawPrice = pricing.displayPrice,
            couponInfo = buildMap {
                put("platformCoupon", pricing.singleCoupon)
                put("shopCoupon", pricing.shopCoupon)
                put("promoDiscount", pricing.promoDiscount)
                put("priceTag", pricing.priceTag)
                put("priceTagType", pricing.priceTagType)
                govSubsidy?.let { putAll(it) }
            },
            // 活动/券已在 couponInfo 拆项，不再用 price−lowestCouponPrice 反推（会重复扣减）
            subsidyAmount = BigDecimal.ZERO,
            freight = BigDecimal.ZERO,
            activityTags = ActivityTags.sanitize(tags),
            sourceUrl = node.path("materialUrl").asText(null),
            platformBrand = meta.brandName,
            platformSpuId = meta.spuId,
            platformCategory = meta.category,
        )
    }

    private fun mapPromotionNode(node: JsonNode, skuId: String): AffiliateItem {
        val price = node.path("unitPrice").decimalOrNull()
            ?: node.path("price").decimalOrNull()
            ?: BigDecimal.ZERO
        return AffiliateItem(
            platform = Platform.JD.code,
            platformItemId = skuId,
            title = node.path("skuName").asText("京东商品"),
            imageUrl = node.path("imgUrl").asText(null),
            shopName = null,
            shopType = "self",
            rawPrice = price,
            couponInfo = emptyMap(),
            subsidyAmount = BigDecimal.ZERO,
            freight = BigDecimal.ZERO,
            activityTags = emptyList(),
            sourceUrl = node.path("materialUrl").asText(null),
        )
    }

    private fun AffiliateItem.withImageFallback(skuId: String): AffiliateItem {
        if (!imageUrl.isNullOrBlank()) return this
        val resolved = imageResolver.resolveMainImage(skuId) ?: return this
        return copy(imageUrl = resolved)
    }

    private fun JsonNode.decimalOrNull(): BigDecimal? {
        if (isMissingNode || isNull) return null
        return asText(null)?.toBigDecimalOrNull() ?: if (isNumber) BigDecimal(asText()) else null
    }
}

private fun JsonNode.firstOrNull(): JsonNode? =
    if (isArray && size() > 0) get(0) else null
