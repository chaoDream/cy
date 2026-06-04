package com.zdsj.affiliate.provider

import com.zdsj.affiliate.Platform
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 轻量可观测性（补充四）：按 provider+platform+op 统计调用次数、成功率、累计耗时。
 * 进程内聚合，可由健康/监控接口读取 [snapshot]，无需引入额外监控栈。
 */
@Component
class AffiliateMetrics {
    private val log = LoggerFactory.getLogger(javaClass)
    private val stats = ConcurrentHashMap<String, Stat>()

    fun record(provider: String, platform: Platform, op: String, success: Boolean, elapsedMs: Long) {
        val stat = stats.computeIfAbsent("$provider|${platform.code}|$op") { Stat() }
        stat.total.incrementAndGet()
        if (success) stat.success.incrementAndGet() else stat.failure.incrementAndGet()
        stat.totalMs.addAndGet(elapsedMs)
        if (!success) {
            log.debug("[affiliate-metrics] miss provider={} platform={} op={} {}ms", provider, platform.code, op, elapsedMs)
        }
    }

    fun snapshot(): Map<String, Map<String, Any>> = stats.mapValues { (_, s) ->
        val total = s.total.get().coerceAtLeast(1)
        mapOf(
            "total" to s.total.get(),
            "success" to s.success.get(),
            "failure" to s.failure.get(),
            "successRate" to "%.1f%%".format(s.success.get() * 100.0 / total),
            "avgMs" to s.totalMs.get() / total,
        )
    }

    private class Stat {
        val total = AtomicLong()
        val success = AtomicLong()
        val failure = AtomicLong()
        val totalMs = AtomicLong()
    }
}
