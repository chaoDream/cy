package com.zdsj.affiliate.pdd

import com.fasterxml.jackson.databind.JsonNode
import com.zdsj.affiliate.AffiliateItem
import com.zdsj.affiliate.PddLinkParser
import com.zdsj.affiliate.Platform
import com.zdsj.common.BizException
import com.zdsj.common.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode

@Service
class PddDdkService(
    private val client: PddDdkClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun fetchFromShareText(linkText: String): AffiliateItem {
        val goodsSign = resolveGoodsSign(linkText)
            ?: throw BizException(ErrorCode.PARSE_FAILED, "暂时没识别出来，可以换一个链接试试")
        return fetchByGoodsSign(goodsSign, linkText)
    }

    fun fetchByGoodsSign(goodsSign: String, sourceLink: String? = null): AffiliateItem {
        queryDetail(goodsSign)?.let { return it.withSource(sourceLink) }
        querySearch(goodsSignList = listOf(goodsSign))?.let { return it.withSource(sourceLink) }

        val title = PddLinkParser.extractShareTitle(sourceLink.orEmpty())
        if (title != null) {
            log.info("多多进宝详情无结果，降级为分享标题 goodsSign={} title={}", goodsSign, title)
            return AffiliateItem(
                platform = Platform.PDD.code,
                platformItemId = goodsSign,
                title = title,
                imageUrl = null,
                shopName = "拼多多",
                shopType = "thirdparty",
                rawPrice = BigDecimal.ZERO,
                couponInfo = emptyMap(),
                subsidyAmount = BigDecimal.ZERO,
                freight = BigDecimal.ZERO,
                activityTags = listOf("多多进宝"),
                sourceUrl = PddLinkParser.extractUrl(sourceLink.orEmpty()),
            )
        }
        throw BizException(ErrorCode.PARSE_FAILED, "商品已下架或不在推广计划中")
    }

    fun fetchItem(itemId: String): AffiliateItem? {
        return when {
            PddLinkParser.isGoodsSign(itemId) -> fetchByGoodsSign(itemId)
            itemId.startsWith("pdd_ps_") -> {
                val url = PddLinkParser.psToUrl(itemId) ?: return null
                fetchFromShareText(url)
            }
            itemId.all { it.isDigit() } -> {
                querySearch(keyword = itemId)?.let { return it }
                null
            }
            else -> null
        }
    }

    fun search(keyword: String, limit: Int): List<AffiliateItem> {
        val data = client.searchGoods(keyword = keyword, pageSize = limit) ?: return emptyList()
        return extractGoodsList(data).mapNotNull { mapGoodsNode(it) }
    }

    /** 批量 goods_sign 查详情（官方 goods.detail，每批建议 ≤20） */
    fun fetchGoodsBatch(goodsSigns: List<String>, customParameters: String? = null): List<AffiliateItem> {
        if (goodsSigns.isEmpty()) return emptyList()
        return goodsSigns.distinct().filter { PddLinkParser.isGoodsSign(it) }.chunked(20).flatMap { chunk ->
            val data = runCatching { client.goodsDetail(chunk, customParameters) }.getOrNull() ?: return@flatMap emptyList()
            val list = data.path("goods_details")
            if (!list.isArray) return@flatMap emptyList()
            list.mapNotNull { mapGoodsNode(it) }
        }
    }

    fun buildCpsLink(goodsSign: String, searchId: String? = null, customParameters: String? = null): String? {
        // 比价预判：备案用户（带 custom_parameters）若被判定为比价订单（佣金归零），不生成购买链接
        if (customParameters != null && isPriceCompare(goodsSign, customParameters)) {
            log.info("拼多多比价预判命中，跳过转链 goodsSign={} cp={}", goodsSign, customParameters)
            return null
        }
        val data = client.generatePromotionUrl(listOf(goodsSign), searchId, customParameters = customParameters)
            ?: return null
        val list = data.path("goods_promotion_url_list")
        if (!list.isArray || list.isEmpty) return null
        val first = list[0]
        return first.path("mobile_url").asText(null)
            ?: first.path("short_url").asText(null)
            ?: first.path("url").asText(null)
    }

    /**
     * 比价预判：predict_promotion_rate == 0 视为比价订单（佣金归零）。
     * 需 pid + custom_parameters 已备案；查不到预判数据时返回 false（不拦截，交后续流程）。
     */
    fun isPriceCompare(goodsSign: String, customParameters: String): Boolean {
        val node = runCatching { client.goodsDetail(listOf(goodsSign), customParameters) }
            .getOrNull()?.let { firstGoods(it.path("goods_details")) }
            ?: runCatching {
                client.searchGoods(goodsSignList = listOf(goodsSign), pageSize = 1, customParameters = customParameters)
            }.getOrNull()?.let { firstGoods(it.path("goods_list")) }
            ?: return false
        val predict = node.path("predict_promotion_rate")
        if (predict.isMissingNode || predict.isNull) return false
        return predict.asInt(-1) == 0
    }

    /** 用户备案（pid + custom_parameters 绑定拼多多用户），返回小程序授权跳转信息 */
    fun bindAuthority(customParameters: String): JsonNode? = client.bindAuthority(customParameters)

    /** 查询是否已备案 */
    fun isAuthorized(customParameters: String): Boolean {
        val data = client.queryAuthority(customParameters) ?: return false
        return data.path("bind_status").asInt(0) == 1 ||
            data.path("is_bind").asBoolean(false) ||
            data.path("data").path("bind_status").asInt(0) == 1
    }

    private fun firstGoods(list: JsonNode): JsonNode? =
        if (list.isArray && !list.isEmpty) list[0] else null

    private fun resolveGoodsSign(linkText: String): String? {
        val url = PddLinkParser.extractUrl(linkText)
        if (url != null) {
            querySearch(keyword = url)?.platformItemId?.let { return it }
        }
        PddLinkParser.extractItemId(linkText)?.let { id ->
            if (PddLinkParser.isGoodsSign(id)) return id
        }
        PddLinkParser.extractShareTitle(linkText)?.let { title ->
            querySearch(keyword = title)?.platformItemId?.let { return it }
        }
        return null
    }

    private fun queryDetail(goodsSign: String): AffiliateItem? {
        val data = runCatching { client.goodsDetail(listOf(goodsSign)) }.getOrNull() ?: return null
        val list = data.path("goods_details")
        if (!list.isArray || list.isEmpty) return null
        return mapGoodsNode(list[0], goodsSign)
    }

    private fun querySearch(keyword: String? = null, goodsSignList: List<String>? = null): AffiliateItem? {
        val data = runCatching {
            client.searchGoods(keyword = keyword, goodsSignList = goodsSignList, pageSize = 1)
        }.getOrNull() ?: return null
        return extractGoodsList(data).firstOrNull()?.let { mapGoodsNode(it) }
    }

    private fun extractGoodsList(data: JsonNode): List<JsonNode> {
        val list = data.path("goods_list")
        if (list.isArray) return list.toList()
        return emptyList()
    }

    private fun mapGoodsNode(node: JsonNode, fallbackSign: String? = null): AffiliateItem? {
        val goodsSign = node.path("goods_sign").asText(null) ?: fallbackSign
        if (goodsSign.isNullOrBlank()) return null
        val title = node.path("goods_name").asText(null) ?: node.path("goods_desc").asText(null)
        if (title.isNullOrBlank()) return null

        val groupPrice = node.path("min_group_price").fenToYuan()
        val normalPrice = node.path("min_normal_price").fenToYuan()
        val rawPrice = when {
            groupPrice > BigDecimal.ZERO -> groupPrice
            normalPrice > BigDecimal.ZERO -> normalPrice
            else -> node.path("coupon_min_group_price").fenToYuan()
        }

        val coupon = node.path("coupon_discount").fenToYuan()
        val merchantType = node.path("merchant_type").asInt(0)
        val shopType = when (merchantType) {
            3 -> "flagship"
            4, 5 -> "flagship"
            else -> "thirdparty"
        }

        val tags = buildList {
            node.path("activity_tags").forEach { tagId ->
                activityTagName(tagId.asInt(0))?.let { add(it) }
            }
            node.path("unified_tags").forEach { add(it.asText()) }
            if (coupon > BigDecimal.ZERO) add("券${coupon.stripTrailingZeros().toPlainString()}元")
        }.distinct()

        return AffiliateItem(
            platform = Platform.PDD.code,
            platformItemId = goodsSign,
            title = title,
            imageUrl = node.path("goods_image_url").asText(null)
                ?: node.path("goods_thumbnail_url").asText(null),
            shopName = node.path("mall_name").asText(null),
            shopType = shopType,
            rawPrice = rawPrice,
            couponInfo = mapOf("platformCoupon" to coupon, "shopCoupon" to BigDecimal.ZERO),
            subsidyAmount = estimateSubsidy(node, rawPrice),
            freight = BigDecimal.ZERO,
            activityTags = tags.ifEmpty { listOf("多多进宝") },
            sourceUrl = node.path("goods_url").asText(null),
        )
    }

    private fun estimateSubsidy(node: JsonNode, rawPrice: BigDecimal): BigDecimal {
        val hasSubsidyTag = node.path("activity_tags").any { it.asInt() == 7 }
        if (!hasSubsidyTag) return BigDecimal.ZERO
        val promo = node.path("promotion_rate").asInt(0)
        return if (promo > 0 && rawPrice > BigDecimal.ZERO) {
            rawPrice.multiply(BigDecimal(promo))
                .divide(BigDecimal(1000), 2, RoundingMode.HALF_UP)
        } else {
            BigDecimal.ZERO
        }
    }

    private fun activityTagName(tagId: Int): String? = when (tagId) {
        4 -> "秒杀"
        7 -> "百亿补贴"
        24 -> "品牌高佣"
        31 -> "品牌黑标"
        10851 -> "千万补贴"
        11879 -> "千万神券"
        else -> null
    }

    private fun AffiliateItem.withSource(sourceLink: String?): AffiliateItem {
        if (sourceUrl != null || sourceLink.isNullOrBlank()) return this
        return copy(sourceUrl = PddLinkParser.extractUrl(sourceLink))
    }

    private fun JsonNode.fenToYuan(): BigDecimal {
        if (isMissingNode || isNull) return BigDecimal.ZERO
        val fen = asText(null)?.toLongOrNull()
            ?: if (isNumber) asLong() else return BigDecimal.ZERO
        return BigDecimal(fen).divide(BigDecimal(100), 2, RoundingMode.HALF_UP)
    }
}
