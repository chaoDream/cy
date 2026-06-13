package com.zdsj.price

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SeedLineRegistryTest {

    @Test
    fun `detects iphone pro max line`() {
        assertEquals(
            "apple-iphone-pro-max",
            SeedLineRegistry.detectLineKey("iPhone 16 Pro Max 256GB 国行"),
        )
    }

    @Test
    fun `groups static seeds by line`() {
        val seeds = listOf(
            com.zdsj.config.PriceSeedProperties.SeedItem(
                name = "iPhone 16 Pro Max 256GB 国行",
                platforms = listOf("jd"),
            ),
            com.zdsj.config.PriceSeedProperties.SeedItem(
                name = "iPhone 15 Pro Max 256GB 国行",
                platforms = listOf("jd", "pdd"),
            ),
        )
        val slots = SeedLineRegistry.groupStaticSeeds(seeds)
        assertEquals(1, slots.size)
        assertEquals("apple-iphone-pro-max", slots.first().lineKey)
        assertEquals(listOf("jd", "pdd"), slots.first().platforms)
    }

    @Test
    fun `generation score prefers newer iphone`() {
        val line = "apple-iphone-pro-max"
        assertTrue(
            ModelGenerationScore.score("iPhone 17 Pro Max", line) >
                ModelGenerationScore.score("iPhone 16 Pro Max", line),
        )
    }

    @Test
    fun `buildSeedName for apple`() {
        assertEquals(
            "iPhone 17 Pro Max 256GB 国行",
            SeedLineRegistry.buildSeedName("iPhone 17 Pro Max", "256GB 国行", "Apple"),
        )
    }
}
