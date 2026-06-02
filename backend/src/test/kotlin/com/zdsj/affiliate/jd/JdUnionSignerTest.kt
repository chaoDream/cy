package com.zdsj.affiliate.jd

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class JdUnionSignerTest {

    @Test
    fun `sign is uppercase md5`() {
        val params = linkedMapOf(
            "app_key" to "test_key",
            "timestamp" to "2024-01-01 12:00:00",
            "format" to "json",
            "method" to "jd.union.open.order.query",
            "v" to "1.0",
            "sign_method" to "md5",
            "360buy_param_json" to "{}",
        )
        val sign = JdUnionSigner.sign(params, "test_secret")
        assertEquals(32, sign.length)
        assertEquals(sign, sign.uppercase())
    }
}
