package com.zdsj.notify

import com.fasterxml.jackson.databind.ObjectMapper
import com.zdsj.config.WechatProperties
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import java.time.Duration

/**
 * 微信小程序 access_token 管理（带 Redis 缓存，避免频繁刷新触发限频）。
 */
@Service
class WechatAccessTokenService(
    private val props: WechatProperties,
    private val redis: RedisTemplate<String, Any>,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val restClient = RestClient.create()
    private val cacheKey = "wechat:access_token"

    fun get(): String? {
        (redis.opsForValue().get(cacheKey) as? String)?.let { return it }
        if (props.appid.isBlank() || props.appid.startsWith("wx_dev")) {
            log.warn("微信未配置，跳过 access_token 获取（mock）")
            return null
        }
        val url = "https://api.weixin.qq.com/cgi-bin/token" +
            "?grant_type=client_credential&appid=${props.appid}&secret=${props.secret}"
        val body = restClient.get().uri(url).retrieve().body(String::class.java)
        val node = objectMapper.readTree(body)
        val token = node.path("access_token").asText(null) ?: return null
        val expires = node.path("expires_in").asLong(7200)
        redis.opsForValue().set(cacheKey, token, Duration.ofSeconds(expires - 300))
        return token
    }
}
