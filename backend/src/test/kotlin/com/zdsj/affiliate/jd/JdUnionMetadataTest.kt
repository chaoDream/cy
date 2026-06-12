package com.zdsj.affiliate.jd

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class JdUnionMetadataTest {

    private val mapper = ObjectMapper()

    @Test
    fun `extracts brand spu and category from goods node`() {
        val node = mapper.readTree(
            """
            {
              "skuId": "1000123",
              "spuId": "2000456",
              "brandName": "一加",
              "categoryInfo": {
                "cid1Name": "手机通讯",
                "cid2Name": "手机",
                "cid3Name": "一加"
              }
            }
            """.trimIndent(),
        )
        val meta = JdUnionMetadata.fromGoodsNode(node)
        assertEquals("一加", meta.brandName)
        assertEquals("2000456", meta.spuId)
        assertEquals("手机通讯/手机/一加", meta.category)
    }
}
