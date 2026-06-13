package com.zdsj.price

import com.zdsj.config.PriceSeedProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 每日采价前自动刷新动态最新机型清单，无需手动调 /refresh-dynamic。
 * 默认 05:55（采价 cron 06:00 之前 5 分钟）。
 */
@Component
@ConditionalOnProperty(prefix = "zdsj.price-seed", name = ["enabled"], havingValue = "true")
class SeedAutoLatestRefreshJob(
    private val props: PriceSeedProperties,
    private val composer: DynamicSeedComposer,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${zdsj.price-seed.auto-latest.refresh-cron:0 55 5 * * ?}")
    fun refreshBeforePoll() {
        if (!props.autoLatest.enabled) return
        val dynamic = composer.refreshNow()
        log.info("动态种子[定时] 完成 static={} dynamic={}", composer.staticCount(), dynamic.size)
    }
}
