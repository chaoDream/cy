package com.zdsj.affiliate.veapi

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class VeapiMapperTest {

    private val mapper = VeapiMapper()
    private val json = ObjectMapper()

    @Test
    fun `maps jd promotion goods with wireless price and self shop`() {
        val node = json.readTree(
            """
            {"skuId":"100330965784","goodsName":"AI宠物猫陪伴机器人","unitPrice":299,
             "wlUnitPrice":289,"imgUrl":"//img14.360buyimg.com/a/jfs/x.jpg","isJdSale":1,
             "materialUrl":"http://item.jd.com/100330965784.html"}
            """.trimIndent(),
        )
        val item = mapper.mapJdPromotionGoods(node)!!
        assertEquals("100330965784", item.platformItemId)
        assertEquals("AI宠物猫陪伴机器人", item.title)
        assertEquals(0, BigDecimal("289").compareTo(item.rawPrice))
        assertEquals("self", item.shopType)
        assertEquals("https://img14.360buyimg.com/a/jfs/x.jpg", item.imageUrl)
        assertTrue(item.activityTags.contains("京东自营"))
    }

    @Test
    fun `jd price falls back to zero when wireless and unit price missing`() {
        val node = json.readTree(
            """{"skuId":"1","goodsName":"无价商品","wlUnitPrice":-1,"isJdSale":0}""",
        )
        val item = mapper.mapJdPromotionGoods(node)!!
        assertEquals(0, BigDecimal.ZERO.compareTo(item.rawPrice))
        assertEquals("thirdparty", item.shopType)
    }

    @Test
    fun `maps pdd goods with fen to yuan and subsidy tag`() {
        val node = json.readTree(
            """
            {"goods_sign":"c9X2_abc","goods_name":"拼多多测试机","min_group_price":99900,
             "min_normal_price":109900,"coupon_discount":1000,"merchant_type":3,
             "goods_image_url":"https://img.pddpic.com/x.jpg","activity_tags":[7],
             "mall_name":"官方旗舰店","goods_url":"https://p.pinduoduo.com/x"}
            """.trimIndent(),
        )
        val item = mapper.mapPddGoods(node)!!
        assertEquals("c9X2_abc", item.platformItemId)
        assertEquals(0, BigDecimal("999.00").compareTo(item.rawPrice))
        assertEquals("flagship", item.shopType)
        assertTrue(item.activityTags.contains("百亿补贴"))
    }

    @Test
    fun `mapping fails validation when title missing`() {
        val node = json.readTree("""{"skuId":"100","unitPrice":10}""")
        assertNull(mapper.mapJdPromotionGoods(node))
    }

    @Test
    fun `mapping fails validation when item id missing`() {
        val node = json.readTree("""{"goods_name":"无ID商品","min_group_price":1000}""")
        assertNull(mapper.mapPddGoods(node))
    }
}
