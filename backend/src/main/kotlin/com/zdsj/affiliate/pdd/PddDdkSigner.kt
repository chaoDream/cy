package com.zdsj.affiliate.pdd

import java.security.MessageDigest

object PddDdkSigner {

    /**
     * MD5(client_secret + key1value1key2value2... + client_secret)，结果大写。
     * 参与签名的参数不含 sign，按 key 字典序排列。
     */
    fun sign(params: Map<String, String>, clientSecret: String): String {
        val sorted = params.filterKeys { it != "sign" }.toSortedMap()
        val raw = buildString {
            append(clientSecret)
            sorted.forEach { (k, v) -> append(k).append(v) }
            append(clientSecret)
        }
        return md5Hex(raw).uppercase()
    }

    private fun md5Hex(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
