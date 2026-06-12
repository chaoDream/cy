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
    /** 京东联盟等平台返回的官方品牌名 */
    val platformBrand: String? = null,
    val platformSpuId: String? = null,
    /** 平台类目路径，如「手机通讯/手机」 */
    val platformCategory: String? = null,
)
