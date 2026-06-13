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

    fun buildCpsLink(platform: Platform, itemId: String, userKey: String? = null): AffiliateResult<String> {
        // 带用户标识时（拼多多比价预判按用户而异）不走共享缓存，避免跨用户串味
        if (userKey == null) {
            cache.getCps(platform, itemId)?.let {
                return AffiliateResult(it.data, "cache", fromCache = true, degraded = it.data == null)
            }
        }
        val ctx = contextFor(platform, bypassCache = false, userKey = userKey)
        val result = runChain(platform, "buildCpsLink") { it.buildCpsLink(ctx, itemId) }
        if (userKey == null) cache.putCps(platform, itemId, result.data)
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

    /** 京粉精选 / 运营频道商品（推荐页等），走 primary → fallback 降级链 */
    fun fetchEliteGoods(platform: Platform, eliteId: Int, limit: Int = 20): AffiliateResult<List<AffiliateItem>> {
        val ctx = contextFor(platform, bypassCache = false)
        return runChain(platform, "fetchEliteGoods") { p ->
            p.fetchEliteGoods(ctx, eliteId, limit).takeIf { it.isNotEmpty() }
        }
    }

    /** 批量直查 SKU / goodsSign（采价专用；空列表亦视为成功响应，由上层按 SKU 逐条降级） */
    fun fetchItemsBatch(
        platform: Platform,
        itemIds: List<String>,
        bypassCache: Boolean = true,
    ): AffiliateResult<List<AffiliateItem>> {
        if (itemIds.isEmpty()) return AffiliateResult(emptyList(), "none")
        val ctx = contextFor(platform, bypassCache = bypassCache)
        val order = providerOrder(platform)
        val route = routeOf(platform)
        var degraded = false
        for ((idx, name) in order.withIndex()) {
            val provider = byName[name] ?: continue
            if (!provider.supports(platform)) {
                degraded = true
                continue
            }
            if (route.autoFailover && breaker.isOpen(name, platform)) {
                degraded = true
                continue
            }
            val start = System.currentTimeMillis()
            var threw = false
            val batch = runCatching { provider.fetchItemsBatch(ctx, itemIds) }.getOrElse {
                threw = true
                log.warn("[affiliate] fetchItemsBatch 失败 provider={} platform={}: {}", name, platform.code, it.message)
                null
            }
            val elapsed = System.currentTimeMillis() - start
            if (batch != null) {
                metrics.record(name, platform, "fetchItemsBatch", batch.isNotEmpty(), elapsed)
                breaker.recordSuccess(name, platform)
                return AffiliateResult(batch, name, degraded = idx > 0 || degraded)
            }
            metrics.record(name, platform, "fetchItemsBatch", false, elapsed)
            if (threw) breaker.recordFailure(name, platform)
            degraded = true
        }
        return AffiliateResult(emptyList(), order.firstOrNull() ?: "none", degraded = true)
    }

    /** 千人千面物料推荐（首页猜你喜欢等），走 primary → fallback 降级链 */
    fun fetchMaterialRecommend(
        platform: Platform,
        eliteId: Int,
        limit: Int = 10,
        userKey: String? = null,
    ): AffiliateResult<List<AffiliateItem>> {
        val ctx = contextFor(platform, bypassCache = false, userKey = userKey)
        return runChain(platform, "fetchMaterialRecommend") { p ->
            p.fetchMaterialRecommend(ctx, eliteId, limit).takeIf { it.isNotEmpty() }
        }
    }

    /** 从分享文案识别平台并拉取（不缓存：入参高基数） */
    fun fetchFromShareText(linkText: String, userKey: String? = null): AffiliateResult<AffiliateItem> {
        val platform = detect(linkText)?.first
        val ctx = platform?.let { contextFor(it, bypassCache = false, userKey = userKey) }
        val order = if (platform != null) providerOrder(platform) else byName.keys.toList()
        var degraded = false
        for ((idx, name) in order.withIndex()) {
            val provider = byName[name] ?: continue
            val item = runCatching { provider.fetchFromShareText(linkText, ctx) }.getOrElse {
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
        /** 搜索/榜单类「无结果」是正常业务空，不应累计熔断失败次数 */
        val softMiss = op in SOFT_MISS_OPS
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
            var threw = false
            val result = runCatching { call(provider) }.getOrElse {
                threw = true
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
            if (threw || !softMiss) {
                breaker.recordFailure(name, platform)
            }
            degraded = true
        }
        return AffiliateResult.empty(order.firstOrNull() ?: "none", degraded = true)
    }

    private companion object {
        val SOFT_MISS_OPS = setOf("search", "fetchEliteGoods", "fetchMaterialRecommend", "fetchItemsBatch")
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

    private fun contextFor(platform: Platform, bypassCache: Boolean, userKey: String? = null): AffiliateContext {
        val mode = when (platform) {
            Platform.JD -> props.veapi.jd.authMode
            Platform.PDD -> props.veapi.pdd.authMode
        }
        return AffiliateContext(
            platform = platform,
            bypassCache = bypassCache,
            authMode = if (mode.equals("public", ignoreCase = true)) AuthMode.PUBLIC else AuthMode.SELF,
            userKey = userKey,
        )
    }
}
