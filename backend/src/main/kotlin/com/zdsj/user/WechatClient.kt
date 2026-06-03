package com.zdsj.user

import com.fasterxml.jackson.databind.ObjectMapper
import com.zdsj.common.BizException
import com.zdsj.common.ErrorCode
import com.zdsj.config.WechatProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

data class Code2SessionResult(val openid: String, val sessionKey: String?)

/**
 * 微信 code2session：用 wx.login 的 code 换 openid。
 * 未配置真实 appid/secret 时（dev）返回基于 code 的稳定 mock openid，便于本地联调。
 */
@Component
class WechatClient(
    private val props: WechatProperties,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val restClient = RestClient.create()

    fun code2Session(code: String): Code2SessionResult {
        if (props.appid.isBlank() || props.appid.startsWith("wx_dev")) {
            log.warn("微信未配置，使用 mock openid（仅限开发）")
            return Code2SessionResult(openid = "mock_openid_$code", sessionKey = null)
        }
        val url = "https://api.weixin.qq.com/sns/jscode2session" +
            "?appid=${props.appid}&secret=${props.secret}&js_code=$code&grant_type=authorization_code"
        val body = restClient.get().uri(url).retrieve().body(String::class.java)
        val node = objectMapper.readTree(body)
        val errcode = node.path("errcode").asInt(0)
        if (errcode != 0) {
            val msg = node.path("errmsg").asText("微信登录失败")
            log.warn("微信 code2session 失败 errcode={} msg={}", errcode, msg)
            throw BizException(ErrorCode.UNAUTHORIZED, "微信登录失败，请重试")
        }
        val openid = node.path("openid").asText(null)
            ?: throw BizException(ErrorCode.UNAUTHORIZED, "微信登录失败，请重试")
        return Code2SessionResult(openid, node.path("session_key").asText(null))
    }
}
