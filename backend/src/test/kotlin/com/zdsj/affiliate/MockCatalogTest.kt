package com.zdsj.affiliate

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class MockCatalogTest {

    @Test
    fun `matches xiaomi 17 share title`() {
        val seed = MockCatalog.matchByKeyword("小米17ProMAX16+512")
        assertNotNull(seed)
        assertEquals("小米", seed!!.brand)
        assertEquals("17 Pro Max", seed.model)
    }

    @Test
    fun `does not default to iphone for unknown keyword`() {
        assertNull(MockCatalog.matchByKeyword("未知商品ABC"))
    }

    @Test
    fun `numeric sku id does not hash to random phone`() {
        assertNull(MockCatalog.byItemIdOrNull("71740439"))
    }
}
