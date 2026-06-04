package com.zdsj.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "zdsj.jwt")
data class JwtProperties(
    val secret: String = "",
    val ttlSeconds: Long = 7776000,
)

@ConfigurationProperties(prefix = "zdsj.wechat")
data class WechatProperties(
    val appid: String = "",
    val secret: String = "",
    val subscribeTemplateId: String = "",
)

@ConfigurationProperties(prefix = "zdsj.affiliate")
data class AffiliateProperties(
    /** 兼容旧开关：true 时默认走 mock（provider 未显式配置时的回退语义） */
    val mock: Boolean = true,
    /** 各平台的提供商路由（维易 / 官方 / mock + 降级链） */
    val provider: ProviderRouting = ProviderRouting(),
    /** 维易 VEAPI 配置 */
    val veapi: Veapi = Veapi(),
    /** Provider 熔断配置 */
    val breaker: Breaker = Breaker(),
    /** Gateway 缓存配置 */
    val cache: Cache = Cache(),
    val pdd: Pdd = Pdd(),
    val jd: Jd = Jd(),
) {
    data class Pdd(val clientId: String = "", val clientSecret: String = "", val pid: String = "")
    data class Jd(
        val appKey: String = "",
        val appSecret: String = "",
        /** 联盟媒体 ID（unionId） */
        val unionId: String = "",
        /** 推广站点 ID（可选；未配置时用 byunionid 转链） */
        val siteId: String = "",
        /** 推广位 ID（可选） */
        val positionId: String = "",
    )

    /** 按平台指定提供商与降级链 */
    data class ProviderRouting(
        val jd: PlatformRoute = PlatformRoute(),
        val pdd: PlatformRoute = PlatformRoute(),
    )

    data class PlatformRoute(
        /** 主提供商：veapi | jd_official | pdd_official | mock */
        val primary: String = "mock",
        /** 降级链，按序尝试 */
        val fallback: List<String> = emptyList(),
        /** 主源熔断时是否自动切到 fallback（false=纯手动） */
        val autoFailover: Boolean = true,
    )

    data class Veapi(
        val baseUrl: String = "https://api.veapi.cn",
        val vekey: String = "",
        /** 可选加签密钥；为空则不加签 */
        val secret: String = "",
        val jd: VeapiJd = VeapiJd(),
        val pdd: VeapiPdd = VeapiPdd(),
    )

    data class VeapiJd(
        /** self（自有联盟号）| public（维易公共号） */
        val authMode: String = "self",
        val sessionkey: String = "",
        /** CPS 归因：你自己的联盟媒体 ID */
        val unionId: String = "",
        val positionId: String = "",
    )

    data class VeapiPdd(
        val authMode: String = "self",
        val sessionkey: String = "",
        /** CPS 归因：你自己的推广位 PID */
        val pid: String = "",
    )

    data class Breaker(
        /** 连续失败达到阈值则熔断 */
        val failThreshold: Int = 3,
        /** 熔断冷却时长（秒） */
        val cooldownSeconds: Long = 60,
    )

    data class Cache(
        val itemTtlSeconds: Long = 600,
        val cpsTtlSeconds: Long = 21600,
        val emptyTtlSeconds: Long = 45,
        val searchTtlSeconds: Long = 120,
    )
}

@ConfigurationProperties(prefix = "zdsj.ai")
data class AiProperties(
    val mock: Boolean = true,
    val baseUrl: String = "",
    val apiKey: String = "",
    val modelHigh: String = "gpt-4o",
    val modelFast: String = "gpt-4o-mini",
)

@ConfigurationProperties(prefix = "zdsj.watch")
data class WatchProperties(
    val pollHotCron: String = "0 0 */2 * * ?",
    val pollUserCron: String = "0 30 */4 * * ?",
    val pollNormalCron: String = "0 0 4 * * ?",
)
