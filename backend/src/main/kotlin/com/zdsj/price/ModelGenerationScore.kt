package com.zdsj.price

/**
 * 机型代际打分：用于比较同产品线静态种子 vs 搜索/目录发现结果。
 */
object ModelGenerationScore {

    fun score(model: String, lineKey: String): Long {
        val m = model.trim()
        return when {
            lineKey.startsWith("apple-iphone") -> iphoneScore(m, lineKey)
            lineKey.startsWith("huawei-mate-x") -> foldScore(m)
            lineKey.startsWith("huawei-") -> numberedScore(
                m,
                """(?i)mate\s*(\d+)""",
                """(?i)pura\s*(\d+)""",
                """(?i)nova\s*(\d+)""",
                lineKey = lineKey,
            )
            lineKey.startsWith("xiaomi-") -> xiaomiScore(m, lineKey)
            lineKey.startsWith("redmi-") -> redmiScore(m, lineKey)
            lineKey.startsWith("oppo-find") -> findXScore(m, lineKey)
            lineKey.startsWith("oppo-") -> numberedScore(m, """(?i)reno\s*(\d+)""", """(?i)a(\d+)""", lineKey = lineKey)
            lineKey.startsWith("oneplus-") -> numberedScore(m, """(?i)ace\s*(\d+)""", lineKey = lineKey)
            lineKey.startsWith("vivo-x") -> xSeriesScore(m, lineKey)
            lineKey.startsWith("vivo-s") -> numberedScore(m, """(?i)s(\d+)""", lineKey = lineKey)
            lineKey.startsWith("vivo-y") -> numberedScore(m, """(?i)y(\d+)""", lineKey = lineKey)
            lineKey.startsWith("iqoo-") -> numberedScore(m, """(?i)neo\s*(\d+)""", lineKey = lineKey)
            lineKey.startsWith("honor-") -> honorScore(m, lineKey)
            else -> 0L
        }
    }

    private fun iphoneScore(m: String, lineKey: String): Long {
        val gen = Regex("""(?i)iphone\s*(\d+)""").find(m)?.groupValues?.get(1)?.toLongOrNull() ?: return 0L
        val tier = when (lineKey) {
            "apple-iphone-pro-max" -> 30L
            "apple-iphone-pro" -> 20L
            else -> 10L
        }
        return gen * 100 + tier
    }

    private fun xiaomiScore(m: String, lineKey: String): Long {
        val gen = Regex("""(?i)(?:小米|xiaomi)\s*(\d+)""").find(m)?.groupValues?.get(1)?.toLongOrNull() ?: return 0L
        val tier = when (lineKey) {
            "xiaomi-ultra" -> 30L
            "xiaomi-number-pro" -> 20L
            else -> 10L
        }
        return gen * 100 + tier
    }

    private fun redmiScore(m: String, lineKey: String): Long {
        return when (lineKey) {
            "redmi-k-flagship", "redmi-k" -> {
                val k = Regex("""(?i)k(\d+)""").find(m)?.groupValues?.get(1)?.toLongOrNull() ?: return 0L
                k * 100 + if (lineKey.endsWith("flagship")) 20 else 10
            }
            "redmi-turbo" -> Regex("""(?i)turbo\s*(\d+)""").find(m)?.groupValues?.get(1)?.toLongOrNull()?.times(100) ?: 0L
            "redmi-note-pro" -> Regex("""(?i)note\s*(\d+)""").find(m)?.groupValues?.get(1)?.toLongOrNull()?.times(100)?.plus(10) ?: 0L
            "redmi-c" -> Regex("""(?i)(\d+)c""").find(m)?.groupValues?.get(1)?.toLongOrNull()?.times(100) ?: 0L
            else -> 0L
        }
    }

    private fun findXScore(m: String, lineKey: String): Long {
        val gen = Regex("""(?i)x(\d+)""").find(m)?.groupValues?.get(1)?.toLongOrNull() ?: return 0L
        val tier = when (lineKey) {
            "oppo-find-x-ultra" -> 30L
            "oppo-find-x-pro" -> 20L
            else -> 10L
        }
        return gen * 100 + tier
    }

    private fun xSeriesScore(m: String, lineKey: String): Long {
        val gen = Regex("""(?i)x(\d{3})""").find(m)?.groupValues?.get(1)?.toLongOrNull() ?: return 0L
        val tier = if (lineKey == "vivo-x-pro") 20L else 10L
        return gen * 100 + tier
    }

    private fun honorScore(m: String, lineKey: String): Long {
        return when (lineKey) {
            "honor-magic-pro", "honor-magic" -> {
                val gen = Regex("""(?i)magic\s*(\d+)""").find(m)?.groupValues?.get(1)?.toLongOrNull() ?: return 0L
                gen * 100 + if (lineKey.endsWith("pro")) 20 else 10
            }
            "honor-number-pro" -> Regex("""(?i)荣耀\s*(\d+)""").find(m)?.groupValues?.get(1)?.toLongOrNull()?.times(100)?.plus(10) ?: 0L
            "honor-x" -> Regex("""(?i)x(\d+)""").find(m)?.groupValues?.get(1)?.toLongOrNull()?.times(100) ?: 0L
            else -> 0L
        }
    }

    private fun foldScore(m: String): Long =
        Regex("""(?i)x(\d)?""").find(m)?.groupValues?.getOrNull(1)?.toLongOrNull()?.times(100)?.plus(50)
            ?: if (m.contains("Mate X", ignoreCase = true)) 100 else 0L

    private fun numberedScore(m: String, vararg patterns: String, lineKey: String): Long {
        for (p in patterns) {
            val gen = Regex(p).find(m)?.groupValues?.getOrNull(1)?.toLongOrNull()
            if (gen != null) {
                val tier = if (lineKey.contains("pro")) 20L else 10L
                return gen * 100 + tier
            }
        }
        return 0L
    }
}
