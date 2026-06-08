package com.zdsj.affiliate

import java.math.BigDecimal
import kotlin.math.abs

/**
 * Mock 商品目录：在未接入真实联盟 API（affiliate.mock=true）时提供确定性数据，
 * 让链接解析 / 比价 / 到手价 / 盯价 / AI 全链路本地可跑通。
 * 真实接入后由 JdRealAdapter / PddRealAdapter 替换。
 */
object MockCatalog {

    data class Seed(
        val keyword: String,
        val title: String,
        val brand: String,
        val model: String,
        val storage: String,
        val color: String,
        val basePrice: Int,
    )

    private val seeds = listOf(
        Seed("iphone16pro", "Apple iPhone 16 Pro 256GB 原色钛金属 国行 5G", "Apple", "iPhone 16 Pro", "256GB", "原色钛金属", 8999),
        Seed("iphone16", "Apple iPhone 16 128GB 群青色 国行", "Apple", "iPhone 16", "128GB", "群青色", 5999),
        Seed("mate70pro", "华为 Mate 70 Pro 256GB 雪域白 国行", "华为", "Mate 70 Pro", "256GB", "雪域白", 6499),
        Seed("xiaomi17promax", "小米17 Pro Max 16+512GB 5G手机 国行", "小米", "17 Pro Max", "512GB", "黑色", 5999),
        Seed("xiaomi15", "小米 15 Pro 512GB 黑色 国行", "小米", "15 Pro", "512GB", "黑色", 5299),
        Seed("vivox200", "vivo X200 Pro 16+512GB 蔡司影像 国行", "vivo", "X200 Pro", "512GB", "钛色", 5999),
    )

    fun matchByKeyword(keyword: String): Seed? {
        val k = keyword.lowercase().replace(" ", "")
        // 长关键词优先，避免「小米17」误命中「小米15」
        return seeds
            .sortedByDescending { it.keyword.length }
            .firstOrNull { seed ->
                k.contains(seed.keyword) ||
                    seed.keyword.contains(k) ||
                    seed.title.contains(keyword, ignoreCase = true) ||
                    keyword.contains(seed.brand, ignoreCase = true) && k.contains(seed.model.lowercase().replace(" ", ""))
            }
    }

    fun byItemId(itemId: String): Seed = byItemIdOrNull(itemId) ?: seeds.first()

    /** 无关键词命中时不瞎猜，避免短链 hash 落到 iPhone */
    fun byItemIdOrNull(itemId: String): Seed? {
        matchByKeyword(itemId)?.let { return it }
        if (itemId.startsWith("jd_") || itemId.startsWith("pdd_")) {
            return matchByKeyword(itemId.removePrefix("jd_").removePrefix("pdd_"))
        }
        return null
    }

    fun genericSeed(itemId: String): Seed = Seed(
        keyword = "generic",
        title = "Mock商品 $itemId",
        brand = "Mock",
        model = itemId,
        storage = "",
        color = "",
        basePrice = 0,
    )

    /** 不同平台对同一 seed 生成略有差异的报价 + 优惠，模拟真实比价场景 */
    fun toItem(platform: Platform, itemId: String, seed: Seed): AffiliateItem {
        val isPdd = platform == Platform.PDD
        // PDD 百亿补贴价更低、京东自营更稳
        val rawPrice = BigDecimal(if (isPdd) seed.basePrice - 300 else seed.basePrice)
        val coupon = if (isPdd) BigDecimal(0) else BigDecimal(200)
        val subsidy = if (isPdd) BigDecimal(500) else BigDecimal(100)
        val shopType = if (isPdd) "flagship" else "self"
        val tags = if (isPdd) listOf("百亿补贴") else listOf("京东自营", "满200减200")
        return AffiliateItem(
            platform = platform.code,
            platformItemId = itemId,
            title = seed.title,
            imageUrl = null,
            shopName = if (isPdd) "${seed.brand}官方旗舰店" else "${seed.brand}京东自营旗舰店",
            shopType = shopType,
            rawPrice = rawPrice,
            couponInfo = mapOf("platformCoupon" to coupon, "shopCoupon" to BigDecimal(0)),
            subsidyAmount = subsidy,
            freight = BigDecimal(0),
            activityTags = tags,
            sourceUrl = "https://${platform.code}.example.com/item/$itemId",
        )
    }
}
