package com.zdsj.affiliate.jd

import java.security.MessageDigest

object JdUnionSigner {

    /** MD5(appSecret + key1value1key2value2... + appSecret)，结果大写 */
    fun sign(params: Map<String, String>, appSecret: String): String {
        val sorted = params.filterKeys { it != "sign" }.toSortedMap()
        val raw = buildString {
            append(appSecret)
            sorted.forEach { (k, v) -> append(k).append(v) }
            append(appSecret)
        }
        return md5Hex(raw).uppercase()
    }

    private fun md5Hex(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
