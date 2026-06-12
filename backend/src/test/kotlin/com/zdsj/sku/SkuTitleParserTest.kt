package com.zdsj.sku

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SkuTitleParserTest {

    private val parser = SkuTitleParser()

    @Test
    fun `infer model from title after brand`() {
        val model = parser.inferModelFromTitle("一加 Ace 6 12GB+256GB 快银", "一加")
        assertEquals("Ace 6", model)
    }

    @Test
    fun `catalog model longest match wins`() {
        val catalog = SkuCatalogSnapshot(
            brandAliasToCanonical = mapOf("realme" to "realme"),
            modelsByBrand = mapOf("realme" to listOf("GT7 Pro", "GT7")),
        )
        val model = parser.extractModel("realme GT7 Pro 12GB 国行", "realme", catalog)
        assertEquals("GT7 Pro", model)
    }
}
