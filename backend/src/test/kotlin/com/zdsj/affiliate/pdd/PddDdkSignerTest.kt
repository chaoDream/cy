package com.zdsj.affiliate.pdd

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PddDdkSignerTest {

    @Test
    fun `sign is uppercase md5`() {
        val params = linkedMapOf(
            "client_id" to "test_id",
            "timestamp" to "1700000000",
            "type" to "pdd.ddk.goods.search",
            "data_type" to "JSON",
        )
        val sign = PddDdkSigner.sign(params, "test_secret")
        assertEquals(32, sign.length)
        assertEquals(sign, sign.uppercase())
    }

    @Test
    fun `sign excludes sign field and sorts keys`() {
        val params = linkedMapOf(
            "z_last" to "2",
            "a_first" to "1",
            "sign" to "should_ignore",
        )
        val sign1 = PddDdkSigner.sign(params, "sec")
        val sign2 = PddDdkSigner.sign(
            linkedMapOf("a_first" to "1", "z_last" to "2"),
            "sec",
        )
        assertEquals(sign1, sign2)
    }
}
