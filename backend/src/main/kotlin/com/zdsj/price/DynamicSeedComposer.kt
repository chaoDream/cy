package com.zdsj.price

import com.zdsj.config.PriceSeedProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant

/**
 * 合并静态 YAML 种子 + 动态发现的最新机型；动态列表按 TTL 缓存，避免每次采价都打搜索。
 */
@Service
class DynamicSeedComposer(
    private val props: PriceSeedProperties,
    private val discovery: LatestModelDiscoveryService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile
    private var cachedDynamic: List<PriceSeedProperties.SeedItem> = emptyList()

    @Volatile
    private var cachedAt: Instant = Instant.EPOCH

    /** 采价 / 管理接口使用的有效种子清单 */
    fun effectiveItems(): List<PriceSeedProperties.SeedItem> {
        val static = props.enabledItems()
        if (!props.autoLatest.enabled) return static
        refreshIfStale()
        return static + cachedDynamic
    }

    fun staticCount(): Int = props.enabledItems().size

    fun dynamicCount(): Int {
        if (!props.autoLatest.enabled) return 0
        refreshIfStale()
        return cachedDynamic.size
    }

    fun dynamicItems(): List<PriceSeedProperties.SeedItem> {
        refreshIfStale()
        return cachedDynamic.toList()
    }

    /** 强制刷新动态层 */
    fun refreshNow(): List<PriceSeedProperties.SeedItem> {
        cachedDynamic = discovery.discoverNewerThanStatic(props.enabledItems())
        cachedAt = Instant.now()
        log.info("动态种子已刷新 static={} dynamic={}", staticCount(), cachedDynamic.size)
        return cachedDynamic
    }

    /** 目录同步等外部事件后调用：下次 effectiveItems 会重新发现 */
    fun invalidateCache() {
        cachedAt = Instant.EPOCH
    }

    private fun refreshIfStale() {
        val ttl = Duration.ofHours(props.autoLatest.cacheHours.coerceAtLeast(1).toLong())
        if (cachedAt.plus(ttl).isAfter(Instant.now()) && cachedDynamic.isNotEmpty()) return
        if (cachedAt.plus(ttl).isAfter(Instant.now()) && cachedDynamic.isEmpty() && cachedAt != Instant.EPOCH) return
        refreshNow()
    }
}
