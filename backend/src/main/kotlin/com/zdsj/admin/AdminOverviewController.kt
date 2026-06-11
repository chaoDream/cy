package com.zdsj.admin

import com.zdsj.common.ApiResponse
import com.zdsj.config.PriceSeedProperties
import com.zdsj.price.SeedPricePollingJob
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime

/**
 * 后台数据总览（只读）：一眼看清服务器里有哪些业务数据、今天定时采价跑没跑、Redis 缓存里有什么。
 * 配套静态看板：classpath:/static/admin/index.html → 浏览器打开 /admin/。
 *
 * 鉴权：/api/admin 路径已在 WebConfig 排除登录拦截（MVP 简化）。生产部署需另加管理员鉴权 / IP 白名单。
 */
@RestController
@RequestMapping("/api/admin")
class AdminOverviewController(
    private val jdbc: JdbcTemplate,
    private val redis: StringRedisTemplate,
    private val seedProps: PriceSeedProperties,
    /** 采价任务是条件化 Bean（zdsj.price-seed.enabled=true 才存在），用 Provider 可选注入。 */
    private val pollingJob: ObjectProvider<SeedPricePollingJob>,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 关注的业务表（flyway_schema_history 等系统表不展示），按业务重要度排序。 */
    private val businessTables = listOf(
        "product_raw", "product_sku", "product_spu", "product_mapping",
        "price_snapshot", "price_seed_binding", "promotion_rule",
        "watch_item", "alert_hit_record",
        "app_user", "user_profile", "user_feedback",
        "ai_analysis_record", "track_event", "operation_log",
    )

    @GetMapping("/overview")
    fun overview(): ApiResponse<Map<String, Any?>> {
        return ApiResponse.ok(
            mapOf(
                "generatedAt" to OffsetDateTime.now().toString(),
                "tables" to tableCounts(),
                "priceSnapshot" to priceSnapshotStats(),
                "priceSeed" to priceSeedConfig(),
                "redis" to redisOverview(),
            ),
        )
    }

    /**
     * 手动立即采价一次（补采今天 / 测试）。同步执行，返回成功失败统计。
     * 采价任务未启用（zdsj.price-seed.enabled=false）时返回错误码。
     */
    @PostMapping("/price-seed/run")
    fun runPriceSeed(): ApiResponse<Map<String, Any?>> {
        val job = pollingJob.ifAvailable
            ?: return ApiResponse.fail(4003, "采价任务未启用（zdsj.price-seed.enabled=false）")
        log.info("收到手动采价请求")
        val r = job.runOnce("手动")
        return ApiResponse.ok(
            mapOf(
                "total" to r.total,
                "success" to r.success,
                "failed" to r.failed,
                "durationMs" to r.durationMs,
                "failures" to r.failures,
            ),
        )
    }

    /** 各业务表行数（单表查询失败不影响整体）。 */
    private fun tableCounts(): List<Map<String, Any?>> = businessTables.map { table ->
        val count = runCatching {
            jdbc.queryForObject("SELECT COUNT(*) FROM $table", Long::class.java)
        }.getOrElse {
            log.warn("统计表行数失败 table={}: {}", table, it.message)
            null
        }
        mapOf("table" to table, "count" to count)
    }

    /**
     * 价格快照统计：今日总量 / 各平台 / 近7日趋势 / 06:00 定时采价信号。
     * 时间一律按 Asia/Shanghai 折算（DB 存的是 timestamptz）。
     */
    private fun priceSnapshotStats(): Map<String, Any?> {
        val tz = "Asia/Shanghai"
        val todayTotal = runCatching {
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM price_snapshot " +
                    "WHERE (captured_at AT TIME ZONE '$tz')::date = (now() AT TIME ZONE '$tz')::date",
                Long::class.java,
            )
        }.getOrNull()

        // 今日各平台数量
        val byPlatform = runCatching {
            jdbc.queryForList(
                "SELECT platform, COUNT(*) AS cnt FROM price_snapshot " +
                    "WHERE (captured_at AT TIME ZONE '$tz')::date = (now() AT TIME ZONE '$tz')::date " +
                    "GROUP BY platform ORDER BY platform",
            ).map { mapOf("platform" to it["platform"], "count" to it["cnt"]) }
        }.getOrDefault(emptyList())

        // 06:00 定时采价信号：今日 05:55~06:15 之间的快照量 + 今日最早一条时间
        val seedWindow = runCatching {
            jdbc.queryForMap(
                "SELECT " +
                    "  COUNT(*) FILTER (WHERE (captured_at AT TIME ZONE '$tz')::time " +
                    "    BETWEEN TIME '05:55' AND TIME '06:15') AS window_cnt, " +
                    "  MIN(captured_at AT TIME ZONE '$tz') AS first_today " +
                    "FROM price_snapshot " +
                    "WHERE (captured_at AT TIME ZONE '$tz')::date = (now() AT TIME ZONE '$tz')::date",
            )
        }.getOrDefault(emptyMap())
        val windowCnt = (seedWindow["window_cnt"] as? Number)?.toLong() ?: 0L

        // 近7日每日数量
        val last7Days = runCatching {
            jdbc.queryForList(
                "SELECT (captured_at AT TIME ZONE '$tz')::date AS d, COUNT(*) AS cnt " +
                    "FROM price_snapshot " +
                    "WHERE captured_at >= now() - INTERVAL '7 days' " +
                    "GROUP BY d ORDER BY d DESC",
            ).map { mapOf("date" to it["d"].toString(), "count" to it["cnt"]) }
        }.getOrDefault(emptyList())

        return mapOf(
            "todayTotal" to todayTotal,
            "todayByPlatform" to byPlatform,
            "seedJobLikelyRanToday" to (windowCnt > 0),
            "seedWindowCount" to windowCnt,
            "firstSnapshotToday" to seedWindow["first_today"]?.toString(),
            "last7Days" to last7Days,
        )
    }

    /** 种子采价配置快照（是否启用 / cron / 商品数）。 */
    private fun priceSeedConfig(): Map<String, Any?> {
        val items = runCatching { seedProps.enabledItems().filter { it.isNameMode() } }.getOrDefault(emptyList())
        return mapOf(
            "enabled" to seedProps.enabled,
            "pollCron" to seedProps.pollCron,
            "enabledItemCount" to items.size,
        )
    }

    /** Redis 概览：总 key 数 + 按前缀(冒号前段)归类计数 + 少量样例 key。 */
    private fun redisOverview(): Map<String, Any?> {
        val keys = runCatching { redis.keys("*") ?: emptySet() }.getOrElse {
            log.warn("扫描 Redis key 失败: {}", it.message)
            return mapOf("available" to false, "error" to it.message)
        }
        val byPrefix = keys.groupingBy { it.substringBefore(':', it) }
            .eachCount()
            .entries.sortedByDescending { it.value }
            .map { mapOf("prefix" to it.key, "count" to it.value) }
        return mapOf(
            "available" to true,
            "totalKeys" to keys.size,
            "byPrefix" to byPrefix,
            "sampleKeys" to keys.take(50).sorted(),
        )
    }
}
