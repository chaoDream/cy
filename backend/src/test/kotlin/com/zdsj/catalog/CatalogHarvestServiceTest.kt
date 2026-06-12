package com.zdsj.catalog

import com.zdsj.affiliate.AffiliateItem
import com.zdsj.affiliate.Platform
import com.zdsj.sku.SkuCatalogReader
import com.zdsj.sku.SkuCatalogSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.ArgumentMatchers.anyString
import java.math.BigDecimal
import java.util.Optional

class CatalogHarvestServiceTest {

    private val brandRepo = mock(CatalogBrandRepository::class.java)
    private val modelRepo = mock(CatalogModelRepository::class.java)
    private val catalogReader = mock(SkuCatalogReader::class.java)
    private val service = CatalogHarvestService(brandRepo, modelRepo, catalogReader)

    @BeforeEach
    fun setup() {
        `when`(catalogReader.snapshot()).thenReturn(
            SkuCatalogSnapshot(mapOf("一加" to "一加", "oneplus" to "一加")),
        )
        `when`(brandRepo.findByCanonicalName(anyString())).thenReturn(Optional.empty())
        `when`(brandRepo.save(org.mockito.ArgumentMatchers.any(CatalogBrand::class.java)))
            .thenAnswer { it.arguments[0] }
        `when`(modelRepo.findByBrandAndModel(anyString(), anyString())).thenReturn(Optional.empty())
        `when`(modelRepo.save(org.mockito.ArgumentMatchers.any(CatalogModel::class.java)))
            .thenAnswer { it.arguments[0] }
    }

    @Test
    fun `harvest phone item upserts brand and model`() {
        val item = AffiliateItem(
            platform = Platform.JD.code,
            platformItemId = "1",
            title = "一加 Ace 6 12GB+256GB 快银",
            imageUrl = null,
            shopName = "京东自营",
            shopType = "self",
            rawPrice = BigDecimal.TEN,
            couponInfo = emptyMap(),
            subsidyAmount = BigDecimal.ZERO,
            freight = BigDecimal.ZERO,
            activityTags = emptyList(),
            sourceUrl = null,
            platformBrand = "一加",
            platformCategory = "手机通讯/手机",
        )
        val counts = service.harvest(item)
        assertEquals(1, counts.brands)
        assertEquals(1, counts.models)
    }

    @Test
    fun `filters non phone items`() {
        val item = AffiliateItem(
            platform = Platform.JD.code,
            platformItemId = "2",
            title = "纯棉毛巾套装",
            imageUrl = null,
            shopName = null,
            shopType = "thirdparty",
            rawPrice = BigDecimal.ONE,
            couponInfo = emptyMap(),
            subsidyAmount = BigDecimal.ZERO,
            freight = BigDecimal.ZERO,
            activityTags = emptyList(),
            sourceUrl = null,
        )
        assertTrue(service.harvest(item).brands == 0)
    }
}
