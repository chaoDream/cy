package com.zdsj.affiliate.jd

import com.fasterxml.jackson.databind.JsonNode
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
                activityTags = listOf("京东联盟"),
                sourceUrl = JdLinkParser.extractUrl(sourceLink.orEmpty()),
            )
        }

        throw BizException(ErrorCode.PARSE_FAILED, "商品已下架或不在推广计划中")
    }

    fun search(keyword: String, limit: Int): List<AffiliateItem> {
        val data = client.searchGoods(keyword, limit) ?: return emptyList()
        return extractGoodsList(data).mapNotNull { mapGoodsNode(it, keyword) }
    }

    fun buildCpsLink(material: String): String? {
        val data = client.convertLink(material) ?: return null
        return data.path("shortURL").asText(null)
            ?: data.path("clickURL").asText(null)
    }

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
        val arr = when {
            data.isArray -> data
            else -> data.path("data").takeIf { it.isArray } ?: return null
        }
        val node = arr.firstOrNull() ?: return null
        return mapPromotionNode(node, skuId)
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

        val price = node.path("priceInfo").path("price").decimalOrNull()
            ?: node.path("lowestPrice").decimalOrNull()
            ?: node.path("price").decimalOrNull()
            ?: BigDecimal.ZERO

        val coupon = node.path("couponInfo").path("couponList").firstOrNull()
        val couponAmount = coupon?.path("discount")?.decimalOrNull() ?: BigDecimal.ZERO

        val shopType = when (node.path("owner").asText("")) {
            "g" -> "self"
            else -> "thirdparty"
        }
        val tags = buildList {
            if (shopType == "self") add("京东自营")
            if (couponAmount > BigDecimal.ZERO) add("券${couponAmount.stripTrailingZeros().toPlainString()}元")
            node.path("skuTagList").forEach { add(it.asText()) }
        }

        return AffiliateItem(
            platform = Platform.JD.code,
            platformItemId = skuId,
            title = title,
            imageUrl = node.path("imageInfo").path("imageList").firstOrNull()?.path("url")?.asText(null),
            shopName = node.path("shopInfo").path("shopName").asText(null),
            shopType = shopType,
            rawPrice = price,
            couponInfo = mapOf("platformCoupon" to couponAmount, "shopCoupon" to BigDecimal.ZERO),
            subsidyAmount = node.path("priceInfo").path("lowestCouponPrice").decimalOrNull()?.let { lcp ->
                (price - lcp).coerceAtLeast(BigDecimal.ZERO)
            } ?: BigDecimal.ZERO,
            freight = BigDecimal.ZERO,
            activityTags = tags,
            sourceUrl = node.path("materialUrl").asText(null),
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
            activityTags = listOf("京东联盟"),
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
