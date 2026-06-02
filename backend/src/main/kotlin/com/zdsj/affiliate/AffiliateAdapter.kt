package com.zdsj.affiliate

import java.math.BigDecimal

enum class Platform(val code: String) {
    JD("jd"), PDD("pdd");

    companion object {
        fun fromCode(code: String): Platform? = entries.firstOrNull { it.code == code }
    }
}

/** 联盟 API 返回的标准化商品数据（不落库前的中间结构） */
data class AffiliateItem(
    val platform: String,
    val platformItemId: String,
    val title: String,
    val imageUrl: String?,
    val shopName: String?,
    val shopType: String?,            // self/flagship/thirdparty
    val rawPrice: BigDecimal,
    val couponInfo: Map<String, Any?>,
    val subsidyAmount: BigDecimal,    // 平台/官方补贴
    val freight: BigDecimal,
    val activityTags: List<String>,
    val sourceUrl: String?,
)

/**
 * 联盟适配层统一接口。各平台（多多进宝/京东联盟）实现，
 * 上层不感知平台差异。仅走官方 API，禁止爬虫/Cookie/模拟登录（PRD §8）。
 */
interface AffiliateAdapter {
    fun platform(): Platform

    /** 从分享文本/淘口令/链接中提取 itemId，无法识别返回 null */
    fun extractItemId(linkText: String): String?

    /** 拉取单品详情 */
    fun fetchItem(itemId: String): AffiliateItem?

    /** 从完整分享文案拉取（短链/口令等场景） */
    fun fetchFromShareText(linkText: String): AffiliateItem? =
        extractItemId(linkText)?.let { fetchItem(it) }

    /** 关键词搜索候选商品 */
    fun search(keyword: String, limit: Int = 10): List<AffiliateItem>

    /** 生成 CPS 转链（带 pid，用于 purchase_click 跳转） */
    fun buildCpsLink(itemId: String): String?
}
