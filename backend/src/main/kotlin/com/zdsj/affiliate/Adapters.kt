package com.zdsj.affiliate

import com.zdsj.affiliate.jd.JdUnionClient
import com.zdsj.affiliate.jd.JdUnionService
import com.zdsj.config.AffiliateProperties
import org.springframework.stereotype.Component
import java.time.Duration

/** 京东联盟适配器（mock 或真实 API） */
@Component
class JdAdapter(
    private val props: AffiliateProperties,
    private val rateLimiter: RateLimiter,
    private val jdUnionClient: JdUnionClient,
    private val jdUnionService: JdUnionService,
) : AffiliateAdapter {

    override fun platform() = Platform.JD

    override fun extractItemId(linkText: String): String? = JdLinkParser.extractItemId(linkText)

    override fun fetchFromShareText(linkText: String): AffiliateItem? {
        guardRate()
        if (useMock()) {
            val id = JdLinkParser.extractItemId(linkText) ?: return null
            return MockCatalog.toItem(Platform.JD, id, MockCatalog.byItemId(id))
        }
        return runCatching { jdUnionService.fetchFromShareText(linkText) }
            .getOrElse { throw it }
    }

    override fun fetchItem(itemId: String): AffiliateItem? {
        guardRate()
        if (useMock()) return MockCatalog.toItem(Platform.JD, itemId, MockCatalog.byItemId(itemId))
        return runCatching { jdUnionService.fetchBySkuId(itemId) }.getOrNull()
    }

    override fun search(keyword: String, limit: Int): List<AffiliateItem> {
        guardRate()
        if (useMock()) {
            val seed = MockCatalog.matchByKeyword(keyword) ?: return emptyList()
            return listOf(MockCatalog.toItem(Platform.JD, "jd_${seed.keyword}", seed))
        }
        return jdUnionService.search(keyword, limit)
    }

    override fun buildCpsLink(itemId: String): String? {
        if (useMock()) return "https://jd.example.com/cps/$itemId?pid=mock"
        val material = "https://item.jd.com/$itemId.html"
        return jdUnionService.buildCpsLink(material)
    }

    private fun useMock(): Boolean = props.mock || !jdUnionClient.isConfigured()

    private fun guardRate() {
        if (!rateLimiter.tryAcquire("jd", maxPerWindow = 600, window = Duration.ofMinutes(1))) {
            throw com.zdsj.common.BizException(com.zdsj.common.ErrorCode.AFFILIATE_RATE_LIMIT, "京东接口限频，请稍后重试")
        }
    }
}

/** 多多进宝适配器 */
@Component
class PddAdapter(
    private val props: AffiliateProperties,
    private val rateLimiter: RateLimiter,
) : AffiliateAdapter {

    override fun platform() = Platform.PDD

    override fun extractItemId(linkText: String): String? {
        if (linkText.contains("pinduoduo") || linkText.contains("yangkeduo") || linkText.contains("拼多多")) {
            return Regex("""\d{6,}""").find(linkText)?.value ?: "pdd_default"
        }
        return null
    }

    override fun fetchItem(itemId: String): AffiliateItem? {
        guardRate()
        if (props.mock) return MockCatalog.toItem(Platform.PDD, itemId, MockCatalog.byItemId(itemId))
        throw NotImplementedError("多多进宝真实接入待配置")
    }

    override fun search(keyword: String, limit: Int): List<AffiliateItem> {
        guardRate()
        if (props.mock) {
            val seed = MockCatalog.matchByKeyword(keyword) ?: return emptyList()
            return listOf(MockCatalog.toItem(Platform.PDD, "pdd_${seed.keyword}", seed))
        }
        throw NotImplementedError("多多进宝搜索真实接入待配置")
    }

    override fun buildCpsLink(itemId: String): String? =
        if (props.mock) "https://pdd.example.com/cps/$itemId?pid=mock"
        else null

    private fun guardRate() {
        if (!rateLimiter.tryAcquire("pdd", maxPerWindow = 600, window = Duration.ofMinutes(1))) {
            throw com.zdsj.common.BizException(com.zdsj.common.ErrorCode.AFFILIATE_RATE_LIMIT, "拼多多接口限频，请稍后重试")
        }
    }
}

/** 适配器注册表：按平台路由，并支持「识别任意链接属于哪个平台」 */
@Component
class AffiliateRegistry(adapters: List<AffiliateAdapter>) {

    private val byPlatform = adapters.associateBy { it.platform() }

    fun get(platform: Platform): AffiliateAdapter =
        byPlatform[platform] ?: error("无适配器: $platform")

    fun detect(linkText: String): Pair<Platform, String>? {
        for (adapter in byPlatform.values) {
            val id = adapter.extractItemId(linkText)
            if (id != null) return adapter.platform() to id
        }
        return null
    }

    fun fetchFromShareText(linkText: String): AffiliateItem? {
        val (platform, _) = detect(linkText) ?: return null
        return get(platform).fetchFromShareText(linkText)
    }
}
