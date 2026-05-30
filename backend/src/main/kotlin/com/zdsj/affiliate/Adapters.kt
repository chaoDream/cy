package com.zdsj.affiliate

import com.zdsj.config.AffiliateProperties
import org.springframework.stereotype.Component
import java.time.Duration

/** 京东联盟适配器（mock 模式下走 MockCatalog；真实接入替换内部实现） */
@Component
class JdAdapter(
    private val props: AffiliateProperties,
    private val rateLimiter: RateLimiter,
) : AffiliateAdapter {

    private val itemIdRegex = Regex("""item\.jd\.com/(\d+)\.html""")

    override fun platform() = Platform.JD

    override fun extractItemId(linkText: String): String? {
        itemIdRegex.find(linkText)?.let { return it.groupValues[1] }
        if (linkText.contains("jd.com") || linkText.contains("京东")) {
            return Regex("""\d{6,}""").find(linkText)?.value
        }
        return null
    }

    override fun fetchItem(itemId: String): AffiliateItem? {
        guardRate()
        if (props.mock) return MockCatalog.toItem(Platform.JD, itemId, MockCatalog.byItemId(itemId))
        // TODO 真实：京东联盟 jd.union.open.goods.promotiongoodsinfo.query
        throw NotImplementedError("京东联盟真实接入待配置")
    }

    override fun search(keyword: String, limit: Int): List<AffiliateItem> {
        guardRate()
        if (props.mock) {
            val seed = MockCatalog.matchByKeyword(keyword) ?: return emptyList()
            return listOf(MockCatalog.toItem(Platform.JD, "jd_${seed.keyword}", seed))
        }
        throw NotImplementedError("京东联盟搜索真实接入待配置")
    }

    override fun buildCpsLink(itemId: String): String? =
        if (props.mock) "https://jd.example.com/cps/$itemId?pid=mock"
        else null // TODO jd.union.open.promotion.common.get 转链

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
        // 拼多多分享文本含 goods_id 或短链；mock 下用数字串近似
        if (linkText.contains("pinduoduo") || linkText.contains("yangkeduo") || linkText.contains("拼多多")) {
            return Regex("""\d{6,}""").find(linkText)?.value ?: "pdd_default"
        }
        return null
    }

    override fun fetchItem(itemId: String): AffiliateItem? {
        guardRate()
        if (props.mock) return MockCatalog.toItem(Platform.PDD, itemId, MockCatalog.byItemId(itemId))
        // TODO 真实：pdd.ddk.goods.detail
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
        else null // TODO pdd.ddk.goods.promotion.url.generate

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

    /** 尝试识别链接所属平台并提取 itemId */
    fun detect(linkText: String): Pair<Platform, String>? {
        for (adapter in byPlatform.values) {
            val id = adapter.extractItemId(linkText)
            if (id != null) return adapter.platform() to id
        }
        return null
    }
}
