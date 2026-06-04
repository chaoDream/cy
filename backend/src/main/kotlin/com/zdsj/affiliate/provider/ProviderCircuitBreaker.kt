package com.zdsj.affiliate.provider

import com.zdsj.affiliate.Platform
import com.zdsj.config.AffiliateProperties
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * 轻量熔断器（补充二/四）：某 provider 对某平台连续失败达阈值，进入冷却期被自动跳过，
 * Gateway 据此切到降级链，冷却期满后自动半恢复（清零重试）。基于 Redis 计数。
 * Redis 不可用时永远视为「闭合」（不熔断），保证可用性。
 */
@Component
class ProviderCircuitBreaker(
    private val redis: RedisTemplate<String, Any>,
    private val props: AffiliateProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val cfg get() = props.breaker

    fun isOpen(provider: String, platform: Platform): Boolean = runCatching {
        redis.hasKey(openKey(provider, platform))
    }.getOrDefault(false)

    fun recordSuccess(provider: String, platform: Platform) {
        runCatching {
            redis.delete(failKey(provider, platform))
            redis.delete(openKey(provider, platform))
        }
    }

    fun recordFailure(provider: String, platform: Platform) {
        runCatching {
            val key = failKey(provider, platform)
            val count = redis.opsForValue().increment(key) ?: 1L
            if (count == 1L) redis.expire(key, Duration.ofSeconds(cfg.cooldownSeconds))
            if (count >= cfg.failThreshold) {
                redis.opsForValue().set(openKey(provider, platform), "1", Duration.ofSeconds(cfg.cooldownSeconds))
                log.warn(
                    "[affiliate-breaker] 熔断开启 provider={} platform={} fails={} 冷却{}s",
                    provider, platform.code, count, cfg.cooldownSeconds,
                )
            }
        }
    }

    private fun failKey(provider: String, platform: Platform) = "aff:breaker:fail:$provider:${platform.code}"
    private fun openKey(provider: String, platform: Platform) = "aff:breaker:open:$provider:${platform.code}"
}
