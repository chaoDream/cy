package com.zdsj.affiliate

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class JdLinkParserTest {

    @Test
    fun `extracts item id from item jd com url`() {
        val id = JdLinkParser.extractItemId("https://item.jd.com/100012345678.html iPhone 16 Pro")
        assertEquals("100012345678", id)
    }

    @Test
    fun `extracts stable id from 3 cn share text without numeric sku`() {
        val text = "【京东】https://3.cn/-2QkINKV?jkl=@F9C6XDupBfbv@ MF8335 「电子AI宠物猫陪伴机器人」"
        val id = JdLinkParser.extractItemId(text)
        assertEquals("jd_short_-2QkINKV", id)
    }

    @Test
    fun `extracts title fallback when only jd keyword and title present`() {
        val id = JdLinkParser.extractItemId("【京东】「电子AI宠物猫陪伴机器人」")
        assertNotNull(id)
        assert(id!!.startsWith("jd_title_"))
    }

    @Test
    fun `returns null for non jd text`() {
        assertNull(JdLinkParser.extractItemId("https://taobao.com/item/123"))
    }

    @Test
    fun `3 cn xiaomi share uses stable short id not url search sku`() {
        val text = "【京东】https://3.cn/2Reu-1L8 「小米17ProMAX16+512」"
        val id = JdLinkParser.extractItemId(text)
        assertEquals("jd_short_2Reu-1L8", id)
        assertEquals("小米17ProMAX16+512", JdLinkParser.extractShareTitle(text))
    }
}
