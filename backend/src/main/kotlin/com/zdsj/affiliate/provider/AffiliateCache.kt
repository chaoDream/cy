package com.zdsj.affiliate.provider

import com.fasterxml.jackson.databind.ObjectMapper
import com.zdsj.affiliate.AffiliateItem
import com.zdsj.affiliate.Platform
import com.zdsj.config.AffiliateProperties
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Gateway 读穿缓存（补充一）：商品 / CPS 链接 / 搜索分别配置 TTL。
 * 空结果短 TTL 兜底，避免对失败源高频打穿。Redis 不可用时静默降级（不缓存）。
 */
@Component
class AffiliateCache(
    private val redis: RedisTemplate<String, Any>,
    private val objectMapper: ObjectMapper,
    private val props: AffiliateProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val cfg get() = props.cache

    private companion object {
        const val EMPTY = "__EMPTY__"
    }

    fun getItem(platform: Platform, itemId: String): CacheHit<AffiliateItem>? =
        readValue("aff:item:${platform.code}:$itemId", AffiliateItem::class.java)

    fun putItem(platform: Platform, itemId: String, item: AffiliateItem?) =
        write("aff:item:${platform.code}:$itemId", item, cfg.itemTtlSeconds)

    fun getCps(platform: Platform, itemId: String): CacheHit<String>? =
        readValue("aff:cps:${platform.code}:$itemId", String::class.java)

    fun putCps(platform: Platform, itemId: String, link: String?) =
        write("aff:cps:${platform.code}:$itemId", link, cfg.cpsTtlSeconds)

    fun getSearch(platform: Platform, keyword: String): CacheHit<List<AffiliateItem>>? {
        val raw = read("aff:search:${platform.code}:${keyword.lowercase().trim()}") ?: return null
        if (raw == EMPTY) return CacheHit(null)
        val type = objectMapper.typeFactory.constructCollectionType(List::class.java, AffiliateItem::class.java)
        return runCatching { CacheHit<List<AffiliateItem>>(objectMapper.readValue(raw, type)) }.getOrNull()
    }

    fun putSearch(platform: Platform, keyword: String, items: List<AffiliateItem>?) =
        write("aff:search:${platform.code}:${keyword.lowercase().trim()}", items, cfg.searchTtlSeconds)

    /** 命中包装：data==null 表示命中的是「空结果」缓存 */
    data class CacheHit<T>(val data: T?)

    private fun <T> readValue(key: String, type: Class<T>): CacheHit<T>? {
        val raw = read(key) ?: return null
        if (raw == EMPTY) return CacheHit(null)
        return runCatching { CacheHit(objectMapper.readValue(raw, type)) }.getOrNull()
    }

    private fun read(key: String): String? = runCatching {
        redis.opsForValue().get(key) as? String
    }.getOrElse {
        log.debug("[affiliate-cache] 读失败 key={} err={}", key, it.message)
        null
    }

    private fun write(key: String, value: Any?, ttlSeconds: Long) {
        val payload = if (value == null) EMPTY else runCatching { objectMapper.writeValueAsString(value) }.getOrNull() ?: return
        val ttl = if (value == null) cfg.emptyTtlSeconds else ttlSeconds
        runCatching {
            redis.opsForValue().set(key, payload, Duration.ofSeconds(ttl))
        }.onFailure { log.debug("[affiliate-cache] 写失败 key={} err={}", key, it.message) }
    }
}
