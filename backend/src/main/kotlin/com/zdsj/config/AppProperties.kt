package com.zdsj.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "zdsj.jwt")
data class JwtProperties(
    val secret: String = "",
    val ttlSeconds: Long = 604800,
)

@ConfigurationProperties(prefix = "zdsj.wechat")
data class WechatProperties(
    val appid: String = "",
    val secret: String = "",
    val subscribeTemplateId: String = "",
)

@ConfigurationProperties(prefix = "zdsj.affiliate")
data class AffiliateProperties(
    val mock: Boolean = true,
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
