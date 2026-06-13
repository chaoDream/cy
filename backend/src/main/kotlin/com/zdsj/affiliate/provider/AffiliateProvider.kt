package com.zdsj.affiliate.provider

import com.zdsj.affiliate.AffiliateItem
import com.zdsj.affiliate.Platform

/** 维易鉴权模式：自有联盟号 / 维易公共号 */
enum class AuthMode { SELF, PUBLIC }

/**
 * 调用上下文：用对象承载入参，新增字段不破坏方法签名（扩展性）。
 */
data class AffiliateContext(
    val platform: Platform,
    /** 盯价等需要新鲜数据时置 true，跳过缓存 */
    val bypassCache: Boolean = false,
    val authMode: AuthMode = AuthMode.SELF,
    val traceId: String? = null,
    /** 用户标识（拼多多 custom_parameters，用于比价预判 / 归因备案）；游客为 null */
    val userKey: String? = null,
)

/**
 * 统一返回包装：携带实际来源 / 是否命中缓存 / 是否降级 / 告警。
 * 业务层可只取 [data]，需要时再读元信息。
 */
data class AffiliateResult<T>(
    val data: T?,
    val source: String,
    val fromCache: Boolean = false,
    val degraded: Boolean = false,
    val warnings: List<String> = emptyList(),
) {
    val hasData: Boolean get() = data != null

    companion object {
        fun <T> empty(
            source: String,
            degraded: Boolean = false,
            warnings: List<String> = emptyList(),
        ): AffiliateResult<T> = AffiliateResult(null, source, false, degraded, warnings)
    }
}

/**
 * 联盟数据提供商统一策略接口。维易 / 京东官方 / 拼多多官方 / mock 各实现一份。
 * 一个提供商可服务多个平台（如维易通吃京东+拼多多）。
 */
interface AffiliateProvider {
    /** 提供商名：veapi | jd_official | pdd_official | mock */
    fun name(): String

    /** 是否支持该平台（未配置密钥时返回 false，Gateway 会自动跳过） */
    fun supports(platform: Platform): Boolean

    /** 主动健康检查（可选实现，默认健康） */
    fun healthy(platform: Platform): Boolean = true

    fun extractItemId(platform: Platform, linkText: String): String?

    fun fetchItem(ctx: AffiliateContext, itemId: String): AffiliateItem?

    /** 从分享文案拉取（内部识别平台），无法识别返回 null */
    fun fetchFromShareText(linkText: String, ctx: AffiliateContext? = null): AffiliateItem?

    fun search(ctx: AffiliateContext, keyword: String, limit: Int = 10): List<AffiliateItem>

    /** 京粉精选 / 运营频道（京东 jingfen eliteId 22/24…；拼多多 channel_type 5/1…），默认不支持 */
    fun fetchEliteGoods(ctx: AffiliateContext, eliteId: Int, limit: Int = 20): List<AffiliateItem> = emptyList()

    /**
     * 千人千面物料推荐（京东 goods.recommend.get，eliteId=1猜你喜欢/2实时热销…；
     * 拼多多 pdd_recommend channel_type=4猜你喜欢…），默认不支持。
     */
    fun fetchMaterialRecommend(ctx: AffiliateContext, eliteId: Int, limit: Int = 10): List<AffiliateItem> = emptyList()

    /** 批量直查（京东 promotiongoodsinfo 最多 100 SKU/次；拼多多 goods_sign_list） */
    fun fetchItemsBatch(ctx: AffiliateContext, itemIds: List<String>): List<AffiliateItem> = emptyList()

    fun buildCpsLink(ctx: AffiliateContext, itemId: String): String?

    /** 联盟字符串 ID → 平台数字 SKU（京东 getNumid）；已是数字则原样返回 */
    fun resolveNumericItemId(ctx: AffiliateContext, itemId: String): String? =
        itemId.takeIf { it.all(Char::isDigit) }
}
