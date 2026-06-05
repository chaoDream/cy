package com.zdsj.affiliate

import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * 联盟 API 限频（PRD §9.4）：基于 Redis 的固定窗口计数。
 * 超限抛出，由上层降级到缓存/错峰。
 */
@Component
class RateLimiter(private val redis: RedisTemplate<String, Any>) {

    /** 在窗口内是否允许调用；Redis 不可用时放行，避免拖垮主链路 */
    fun tryAcquire(key: String, maxPerWindow: Long, window: Duration): Boolean =
        runCatching {
            val redisKey = "ratelimit:$key"
            val count = redis.opsForValue().increment(redisKey) ?: 1L
            if (count == 1L) {
                redis.expire(redisKey, window)
            }
            count <= maxPerWindow
        }.getOrDefault(true)
}
