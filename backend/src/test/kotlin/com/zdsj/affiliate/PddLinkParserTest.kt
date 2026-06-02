package com.zdsj.affiliate

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PddLinkParserTest {

    @Test
    fun `extracts ps param from yangkeduo url`() {
        val id = PddLinkParser.extractItemId("https://mobile.yangkeduo.com/goods1.html?ps=FuXUG9Y3SE")
        assertEquals("pdd_ps_FuXUG9Y3SE", id)
    }

    @Test
    fun `ps to url roundtrip`() {
        val url = PddLinkParser.psToUrl("pdd_ps_FuXUG9Y3SE")
        assertEquals("https://mobile.yangkeduo.com/goods1.html?ps=FuXUG9Y3SE", url)
    }

    @Test
    fun `goods sign detection`() {
        assertEquals(
            true,
            PddLinkParser.isGoodsSign("c9r2omogKFFAc7WBwvbZU1ikIb16_J3CTa8HNN"),
        )
        assertEquals(false, PddLinkParser.isGoodsSign("pdd_ps_FuXUG9Y3SE"))
    }

    @Test
    fun `extracts numeric goods id`() {
        val id = PddLinkParser.extractItemId("https://mobile.yangkeduo.com/goods.html?goods_id=1234567890")
        assertEquals("1234567890", id)
    }
}
