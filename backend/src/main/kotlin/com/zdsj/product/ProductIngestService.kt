package com.zdsj.product

import com.zdsj.affiliate.AffiliateItem
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.OffsetDateTime

/**
 * 将联盟返回的商品落库为 product_raw（upsert），供后续 SKU 映射 / 价格快照引用。
 */
@Service
class ProductIngestService(private val rawRepo: ProductRawRepository) {

    @Transactional
    fun upsert(item: AffiliateItem): ProductRaw {
        val existing = rawRepo.findByPlatformAndPlatformItemId(item.platform, item.platformItemId)
        val entity = existing.orElseGet { ProductRaw() }
        entity.platform = item.platform
        entity.platformItemId = item.platformItemId
        entity.title = item.title
        entity.imageUrl = item.imageUrl
        entity.shopName = item.shopName
        entity.shopType = item.shopType
        entity.rawPrice = item.rawPrice
        entity.couponInfo = item.couponInfo.toMutableMap()
        entity.activityTags = item.activityTags.toMutableList()
        entity.sourceUrl = item.sourceUrl
        entity.updatedAt = OffsetDateTime.now()
        return rawRepo.save(entity)
    }

    fun toAffiliateItem(raw: ProductRaw): AffiliateItem = AffiliateItem(
        platform = raw.platform,
        platformItemId = raw.platformItemId,
        title = raw.title,
        imageUrl = raw.imageUrl,
        shopName = raw.shopName,
        shopType = raw.shopType,
        rawPrice = raw.rawPrice ?: BigDecimal.ZERO,
        couponInfo = raw.couponInfo,
        subsidyAmount = (raw.couponInfo["subsidy"] as? Number)?.let { BigDecimal(it.toString()) } ?: BigDecimal.ZERO,
        freight = BigDecimal.ZERO,
        activityTags = raw.activityTags,
        sourceUrl = raw.sourceUrl,
    )
}
