package com.zdsj.affiliate.provider

import com.zdsj.affiliate.AffiliateItem
import com.zdsj.affiliate.JdLinkParser
import com.zdsj.affiliate.MockCatalog
import com.zdsj.affiliate.PddLinkParser
import com.zdsj.affiliate.Platform
import org.springframework.stereotype.Component

/**
 * Mock 提供商：包装 MockCatalog，本地/离线全链路可跑通。
 * 永远 supports，可作为降级链最末端兜底。
 */
@Component
class MockProvider : AffiliateProvider {

    override fun name() = "mock"

    override fun supports(platform: Platform) = true

    override fun extractItemId(platform: Platform, linkText: String): String? = when (platform) {
        Platform.JD -> JdLinkParser.extractItemId(linkText)
        Platform.PDD -> PddLinkParser.extractItemId(linkText)
    }

    override fun fetchItem(ctx: AffiliateContext, itemId: String): AffiliateItem =
        MockCatalog.toItem(ctx.platform, itemId, MockCatalog.byItemId(itemId))

    override fun fetchFromShareText(linkText: String): AffiliateItem? {
        val (platform, id) = detect(linkText) ?: return null
        return MockCatalog.toItem(platform, id, MockCatalog.byItemId(id))
    }

    override fun search(ctx: AffiliateContext, keyword: String, limit: Int): List<AffiliateItem> {
        val seed = MockCatalog.matchByKeyword(keyword) ?: return emptyList()
        return listOf(MockCatalog.toItem(ctx.platform, "${ctx.platform.code}_${seed.keyword}", seed))
    }

    override fun buildCpsLink(ctx: AffiliateContext, itemId: String): String =
        "https://${ctx.platform.code}.example.com/cps/$itemId?pid=mock"

    private fun detect(linkText: String): Pair<Platform, String>? {
        JdLinkParser.extractItemId(linkText)?.let { return Platform.JD to it }
        PddLinkParser.extractItemId(linkText)?.let { return Platform.PDD to it }
        return null
    }
}
