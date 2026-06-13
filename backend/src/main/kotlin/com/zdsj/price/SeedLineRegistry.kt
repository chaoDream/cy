package com.zdsj.price

import com.zdsj.config.PriceSeedProperties

/**
 * 从静态种子名归纳「品牌 + 产品线」槽位，供最新机型发现使用。
 * 40 条 YAML 种子定义覆盖范围；动态层按槽位追加比静态更新的型号。
 */
object SeedLineRegistry {

    data class LineSlot(
        val lineKey: String,
        /** SkuTitleParser / catalog 用的 canonical brand */
        val brand: String,
        val searchKeyword: String,
        val modelPattern: Regex,
        val storageSuffix: String,
        val platforms: List<String>,
        val staticSeedNames: List<String>,
    )

    private data class LineDef(
        val brand: String,
        val searchKeyword: String,
        val modelPattern: Regex,
    )

    private val lineDefs = mapOf(
        "apple-iphone-pro-max" to LineDef("Apple", "iPhone Pro Max 手机 国行", Regex("""(?i)iphone\s*\d+.*pro\s*max""")),
        "apple-iphone-pro" to LineDef("Apple", "iPhone Pro 手机 国行", Regex("""(?i)iphone\s*\d+\s*pro(?!.*max)""")),
        "apple-iphone" to LineDef("Apple", "iPhone 手机 国行", Regex("""(?i)iphone\s*\d+(?!.*pro)""")),
        "huawei-mate-pro" to LineDef("华为", "华为 Mate Pro 手机 国行", Regex("""(?i)mate\s*\d+.*pro""")),
        "huawei-mate" to LineDef("华为", "华为 Mate 手机 国行", Regex("""(?i)mate\s*\d+(?!.*pro|.*x)""")),
        "huawei-pura-pro" to LineDef("华为", "华为 Pura Pro 手机 国行", Regex("""(?i)pura\s*\d+.*pro""")),
        "huawei-pura" to LineDef("华为", "华为 Pura 手机 国行", Regex("""(?i)pura\s*\d+(?!.*pro)""")),
        "huawei-nova-pro" to LineDef("华为", "华为 nova Pro 手机 国行", Regex("""(?i)nova\s*\d+.*pro""")),
        "huawei-mate-x" to LineDef("华为", "华为 Mate X 折叠 国行", Regex("""(?i)mate\s*x\d*""")),
        "xiaomi-number-pro" to LineDef("小米", "小米 Pro 手机 国行", Regex("""(?i)(?:小米|xiaomi)\s*\d+.*pro(?!.*max)""")),
        "xiaomi-number" to LineDef("小米", "小米 数字 手机 国行", Regex("""(?i)(?:小米|xiaomi)\s*\d+(?!.*pro|.*ultra)""")),
        "xiaomi-ultra" to LineDef("小米", "小米 Ultra 手机 国行", Regex("""(?i)(?:小米|xiaomi)\s*\d+.*ultra""")),
        "redmi-k-flagship" to LineDef("Redmi", "Redmi K 至尊 手机", Regex("""(?i)k\d+.*至尊|k\d+.*ultra""")),
        "redmi-k" to LineDef("Redmi", "Redmi K 手机 国行", Regex("""(?i)redmi\s*k\d+(?!.*至尊)""")),
        "redmi-turbo" to LineDef("Redmi", "Redmi Turbo 手机 国行", Regex("""(?i)turbo\s*\d+""")),
        "redmi-note-pro" to LineDef("Redmi", "Redmi Note Pro 手机 国行", Regex("""(?i)note\s*\d+.*pro""")),
        "redmi-c" to LineDef("Redmi", "Redmi 数字 手机 国行", Regex("""(?i)redmi\s*\d+c""")),
        "oppo-find-x-pro" to LineDef("OPPO", "OPPO Find X Pro 手机 国行", Regex("""(?i)find\s*x\d+.*pro(?!.*ultra)""")),
        "oppo-find-x" to LineDef("OPPO", "OPPO Find X 手机 国行", Regex("""(?i)find\s*x\d+(?!.*pro|.*ultra)""")),
        "oppo-find-x-ultra" to LineDef("OPPO", "OPPO Find X Ultra 手机 国行", Regex("""(?i)find\s*x\d+.*ultra""")),
        "oppo-reno-pro" to LineDef("OPPO", "OPPO Reno Pro 手机 国行", Regex("""(?i)reno\s*\d+.*pro""")),
        "oppo-reno" to LineDef("OPPO", "OPPO Reno 手机 国行", Regex("""(?i)reno\s*\d+(?!.*pro)""")),
        "oppo-a" to LineDef("OPPO", "OPPO A 手机 国行", Regex("""(?i)oppo\s*a\d+""")),
        "oneplus-ace-pro" to LineDef("一加", "一加 Ace Pro 手机 国行", Regex("""(?i)ace\s*\d+.*pro""")),
        "vivo-x-pro" to LineDef("vivo", "vivo X Pro 手机 国行", Regex("""(?i)x\d{3}.*pro(?!.*ultra)""")),
        "vivo-x" to LineDef("vivo", "vivo X 手机 国行", Regex("""(?i)x\d{3}(?!.*pro)""")),
        "vivo-s-pro" to LineDef("vivo", "vivo S Pro 手机 国行", Regex("""(?i)s\d+.*pro""")),
        "vivo-y" to LineDef("vivo", "vivo Y 手机 国行", Regex("""(?i)y\d+""")),
        "iqoo-neo-pro" to LineDef("vivo", "iQOO Neo Pro 手机 国行", Regex("""(?i)iqoo\s*neo\s*\d+.*pro""")),
        "honor-magic-pro" to LineDef("荣耀", "荣耀 Magic Pro 手机 国行", Regex("""(?i)magic\s*\d+.*pro""")),
        "honor-magic" to LineDef("荣耀", "荣耀 Magic 手机 国行", Regex("""(?i)magic\s*\d+(?!.*pro)""")),
        "honor-number-pro" to LineDef("荣耀", "荣耀 数字 Pro 手机 国行", Regex("""(?i)荣耀\s*\d+.*pro""")),
        "honor-x" to LineDef("荣耀", "荣耀 X 手机 国行", Regex("""(?i)荣耀\s*x\d+""")),
    )

    fun groupStaticSeeds(seeds: List<PriceSeedProperties.SeedItem>): List<LineSlot> =
        seeds.mapNotNull { seed ->
            val lineKey = detectLineKey(seed.name) ?: return@mapNotNull null
            val def = lineDefs[lineKey] ?: return@mapNotNull null
            lineKey to seed
        }
            .groupBy({ it.first }, { it.second })
            .map { (lineKey, group) ->
                val def = lineDefs.getValue(lineKey)
                LineSlot(
                    lineKey = lineKey,
                    brand = def.brand,
                    searchKeyword = def.searchKeyword,
                    modelPattern = def.modelPattern,
                    storageSuffix = extractStorageSuffix(group.first().name),
                    platforms = group.flatMap { it.platforms }.distinct(),
                    staticSeedNames = group.map { it.name },
                )
            }

    internal fun detectLineKey(seedName: String): String? {
        val n = seedName.trim()
        return when {
            n.contains("iPhone", ignoreCase = true) && n.contains("Pro Max", ignoreCase = true) -> "apple-iphone-pro-max"
            n.contains("iPhone", ignoreCase = true) && Regex("""Pro(?!.*Max)""", RegexOption.IGNORE_CASE).containsMatchIn(n) -> "apple-iphone-pro"
            n.contains("iPhone", ignoreCase = true) -> "apple-iphone"
            n.contains("Mate X", ignoreCase = true) -> "huawei-mate-x"
            n.contains("Mate", ignoreCase = true) && n.contains("Pro", ignoreCase = true) -> "huawei-mate-pro"
            n.contains("Mate", ignoreCase = true) -> "huawei-mate"
            n.contains("Pura", ignoreCase = true) && n.contains("Pro", ignoreCase = true) -> "huawei-pura-pro"
            n.contains("Pura", ignoreCase = true) -> "huawei-pura"
            n.contains("nova", ignoreCase = true) && n.contains("Pro", ignoreCase = true) -> "huawei-nova-pro"
            n.contains("Redmi", ignoreCase = true) && n.contains("至尊") && n.contains("K", ignoreCase = true) -> "redmi-k-flagship"
            n.contains("Redmi", ignoreCase = true) && n.contains("K", ignoreCase = true) -> "redmi-k"
            n.contains("Turbo", ignoreCase = true) -> "redmi-turbo"
            n.contains("Note", ignoreCase = true) && n.contains("Pro", ignoreCase = true) -> "redmi-note-pro"
            n.contains("Redmi", ignoreCase = true) && Regex("""\d+C""").containsMatchIn(n) -> "redmi-c"
            n.contains("Find X", ignoreCase = true) && n.contains("Ultra", ignoreCase = true) -> "oppo-find-x-ultra"
            n.contains("Find X", ignoreCase = true) && n.contains("Pro", ignoreCase = true) -> "oppo-find-x-pro"
            n.contains("Find X", ignoreCase = true) -> "oppo-find-x"
            n.contains("Reno", ignoreCase = true) && n.contains("Pro", ignoreCase = true) -> "oppo-reno-pro"
            n.contains("Reno", ignoreCase = true) -> "oppo-reno"
            Regex("""OPPO\s+A\d+""", RegexOption.IGNORE_CASE).containsMatchIn(n) -> "oppo-a"
            n.contains("Ace", ignoreCase = true) && n.contains("Pro", ignoreCase = true) -> "oneplus-ace-pro"
            n.contains("iQOO", ignoreCase = true) && n.contains("Neo", ignoreCase = true) -> "iqoo-neo-pro"
            Regex("""X\d{3}""").containsMatchIn(n) && n.contains("Pro", ignoreCase = true) -> "vivo-x-pro"
            Regex("""X\d{3}""").containsMatchIn(n) -> "vivo-x"
            Regex("""S\d+""").containsMatchIn(n) && n.contains("Pro", ignoreCase = true) -> "vivo-s-pro"
            Regex("""Y\d+""").containsMatchIn(n) -> "vivo-y"
            n.contains("Magic", ignoreCase = true) && n.contains("Pro", ignoreCase = true) -> "honor-magic-pro"
            n.contains("Magic", ignoreCase = true) -> "honor-magic"
            Regex("""荣耀\s*\d+""").containsMatchIn(n) && n.contains("Pro", ignoreCase = true) -> "honor-number-pro"
            Regex("""荣耀\s*X\d+""", RegexOption.IGNORE_CASE).containsMatchIn(n) -> "honor-x"
            n.startsWith("小米") && n.contains("Ultra", ignoreCase = true) -> "xiaomi-ultra"
            n.startsWith("小米") && n.contains("Pro", ignoreCase = true) -> "xiaomi-number-pro"
            n.startsWith("小米") -> "xiaomi-number"
            else -> null
        }
    }

    internal fun extractStorageSuffix(seedName: String): String {
        Regex("""(\d+\s?(?:GB|TB)(?:\s*\+\s*\d+\s?(?:GB|TB))?\s*国行)""", RegexOption.IGNORE_CASE)
            .find(seedName)?.value?.let { return it }
        return "12GB+256GB 国行"
    }

    fun buildSeedName(model: String, storageSuffix: String, brand: String): String {
        val trimmed = model.trim()
        if (trimmed.startsWith("iPhone", ignoreCase = true)) return "$trimmed $storageSuffix"
        if (trimmed.startsWith("Redmi", ignoreCase = true)) return "$trimmed $storageSuffix"
        if (brand == "一加" && !trimmed.startsWith("一加")) return "一加 $trimmed $storageSuffix"
        if (trimmed.startsWith(brand, ignoreCase = true)) return "$trimmed $storageSuffix"
        return "$brand $trimmed $storageSuffix"
    }

    fun matchesLine(model: String, slot: LineSlot): Boolean = slot.modelPattern.containsMatchIn(model)
}
