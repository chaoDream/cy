package com.zdsj.affiliate

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ActivityTagsTest {

    @Test
    fun `sanitize removes internal provider placeholders`() {
        val raw = listOf("京东联盟", "京东自营", "维易", "维易·京东联盟", "券100元", "多多进宝")
        assertEquals(listOf("京东自营", "券100元"), ActivityTags.sanitize(raw))
    }
}
