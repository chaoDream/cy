package com.zdsj.notify

import com.fasterxml.jackson.databind.ObjectMapper
import com.zdsj.config.WechatProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import java.math.BigDecimal

/**
 * 降价提醒触达（PRD §5.5）：微信订阅消息 subscribeMessage.send。
 * 注意一次订阅一次下发的限制 → 下发后需在小程序端引导用户复订。
 */
@Service
class NotifyService(
    private val props: WechatProperties,
    private val tokenService: WechatAccessTokenService,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val restClient = RestClient.create()

    fun sendPriceDropAlert(
        openid: String,
        productTitle: String,
        currentPrice: BigDecimal,
        targetPrice: BigDecimal,
        page: String,
    ): Boolean {
        val token = tokenService.get()
        if (token == null) {
            log.info("[mock 订阅消息] → {} 商品[{}] 当前到手价 {} 已低于目标价 {}", openid, productTitle, currentPrice, targetPrice)
            return true
        }
        val url = "https://api.weixin.qq.com/cgi-bin/message/subscribe/send?access_token=$token"
        val payload = mapOf(
            "touser" to openid,
            "template_id" to props.subscribeTemplateId,
            "page" to page,
            "miniprogram_state" to "formal",
            "data" to mapOf(
                "thing1" to mapOf("value" to productTitle.take(20)),
                "amount2" to mapOf("value" to "¥$currentPrice"),
                "amount3" to mapOf("value" to "¥$targetPrice"),
            ),
        )
        return runCatching {
            val resp = restClient.post().uri(url).body(payload).retrieve().body(String::class.java)
            val errcode = objectMapper.readTree(resp).path("errcode").asInt(-1)
            if (errcode != 0) log.warn("订阅消息下发失败: {}", resp)
            errcode == 0
        }.getOrElse {
            log.warn("订阅消息下发异常: {}", it.message)
            false
        }
    }
}
