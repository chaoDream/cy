package com.zdsj.affiliate.provider

import com.zdsj.affiliate.AffiliateItem
import com.zdsj.affiliate.Platform
import com.zdsj.config.AffiliateProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * 联盟门面：业务层唯一入口。按配置选择提供商，执行降级链，并叠加缓存 / 熔断 / 监控。
 * 业务层永不感知底层是维易还是官方 —— 只依赖本类与统一模型 AffiliateItem。
 */
@Service
class AffiliateGateway(
    providers: List<AffiliateProvider>,
    private val props: AffiliateProperties,
    private val cache: AffiliateCache,
    private val breaker: ProviderCircuitBreaker,
    private val metrics: AffiliateMetrics,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val byName = providers.associateBy { it.name() }

    fun fetchItem(platform: Platform, itemId: String, bypassCache: Boolean = false): AffiliateResult<AffiliateItem> {
        if (!bypassCache) {
            cache.getItem(platform, itemId)?.let {
                return AffiliateResult(it.data, "cache", fromCache = true, degraded = it.data == null)
            }
        }
        val ctx = contextFor(platform, bypassCache)
        val result = runChain(platform, "fetchItem") { it.fetchItem(ctx, itemId) }
        cache.putItem(platform, itemId, result.data)
        return result
    }

    fun buildCpsLink(platform: Platform, itemId: String): AffiliateResult<String> {
        cache.getCps(platform, itemId)?.let {
            return AffiliateResult(it.data, "cache", fromCache = true, degraded = it.data == null)
        }
        val ctx = contextFor(platform, bypassCache = false)
        val result = runChain(platform, "buildCpsLink") { it.buildCpsLink(ctx, itemId) }
        cache.putCps(platform, itemId, result.data)
        return result
    }

    fun search(platform: Platform, keyword: String, limit: Int = 10): AffiliateResult<List<AffiliateItem>> {
        cache.getSearch(platform, keyword)?.let {
            return AffiliateResult(it.data, "cache", fromCache = true, degraded = it.data == null)
        }
        val ctx = contextFor(platform, bypassCache = false)
        val result = runChain(platform, "search") { p ->
            p.search(ctx, keyword, limit).takeIf { it.isNotEmpty() }
        }
        cache.putSearch(platform, keyword, result.data)
        return result
    }

    /** 从分享文案识别平台并拉取（不缓存：入参高基数） */
    fun fetchFromShareText(linkText: String): AffiliateResult<AffiliateItem> {
        val platform = detect(linkText)?.first
        val order = if (platform != null) providerOrder(platform) else byName.keys.toList()
        var degraded = false
        for ((idx, name) in order.withIndex()) {
            val provider = byName[name] ?: continue
            val item = runCatching { provider.fetchFromShareText(linkText) }.getOrElse {
                log.warn("[affiliate] fetchFromShareText 失败 provider={}: {}", name, it.message)
                degraded = true
                null
            }
            if (item != null) return AffiliateResult(item, name, degraded = idx > 0 || degraded)
            degraded = true
        }
        return AffiliateResult.empty("none", degraded = true)
    }

    /** 识别链接属于哪个平台 + itemId */
    fun detect(linkText: String): Pair<Platform, String>? {
        for (platform in Platform.entries) {
            for (name in providerOrder(platform)) {
                val provider = byName[name] ?: continue
                val id = runCatching { provider.extractItemId(platform, linkText) }.getOrNull()
                if (id != null) return platform to id
            }
        }
        return null
    }

    /** 图片解析收口（供 ProductImageController 使用） */
    fun resolveImage(platform: Platform, itemId: String): String? =
        fetchItem(platform, itemId).data?.imageUrl?.takeIf { it.isNotBlank() }

    /** Provider 健康度快照（监控/排障用） */
    fun metricsSnapshot(): Map<String, Map<String, Any>> = metrics.snapshot()

    /**
     * 执行降级链：primary → fallback，第一个非空结果返回。
     * 每个 provider 调用叠加熔断判定 + 成功率/耗时埋点。
     */
    private fun <T> runChain(
        platform: Platform,
        op: String,
        call: (AffiliateProvider) -> T?,
    ): AffiliateResult<T> {
        val order = providerOrder(platform)
        val route = routeOf(platform)
        var degraded = false
        for ((idx, name) in order.withIndex()) {
            val provider = byName[name]
            if (provider == null) {
                log.warn("[affiliate] 未知 provider={}，跳过", name)
                continue
            }
            if (!provider.supports(platform)) {
                degraded = true
                continue
            }
            // 熔断开启：自动降级模式下跳过该源
            if (route.autoFailover && breaker.isOpen(name, platform)) {
                log.debug("[affiliate] {} 已熔断，跳过 provider={} platform={}", op, name, platform.code)
                degraded = true
                continue
            }

            val start = System.currentTimeMillis()
            val result = runCatching { call(provider) }.getOrElse {
                log.warn("[affiliate] {} 失败 provider={} platform={}: {}", op, name, platform.code, it.message)
                null
            }
            val elapsed = System.currentTimeMillis() - start
            val success = result != null
            metrics.record(name, platform, op, success, elapsed)

            if (success) {
                breaker.recordSuccess(name, platform)
                return AffiliateResult(result, name, degraded = idx > 0 || degraded)
            }
            breaker.recordFailure(name, platform)
            degraded = true
        }
        return AffiliateResult.empty(order.firstOrNull() ?: "none", degraded = true)
    }

    private fun providerOrder(platform: Platform): List<String> {
        val route = routeOf(platform)
        val chain = mutableListOf(route.primary)
        if (route.autoFailover) chain.addAll(route.fallback)
        return chain.filter { it.isNotBlank() }.distinct()
    }

    private fun routeOf(platform: Platform): AffiliateProperties.PlatformRoute = when (platform) {
        Platform.JD -> props.provider.jd
        Platform.PDD -> props.provider.pdd
    }

    private fun contextFor(platform: Platform, bypassCache: Boolean): AffiliateContext {
        val mode = when (platform) {
            Platform.JD -> props.veapi.jd.authMode
            Platform.PDD -> props.veapi.pdd.authMode
        }
        return AffiliateContext(
            platform = platform,
            bypassCache = bypassCache,
            authMode = if (mode.equals("public", ignoreCase = true)) AuthMode.PUBLIC else AuthMode.SELF,
        )
    }
}
