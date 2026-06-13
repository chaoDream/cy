package com.zdsj.admin

import com.zdsj.common.ApiResponse
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/admin")
class AdminAnalyticsController(private val jdbc: JdbcTemplate) {

    private val tz = "Asia/Shanghai"

    @GetMapping("/analytics")
    fun analytics(@RequestParam(defaultValue = "7") days: Int): ApiResponse<Map<String, Any?>> {
        val safeDays = days.coerceIn(1, 90)
        return ApiResponse.ok(
            mapOf(
                "dau" to dau(safeDays),
                "pageViews" to pageViews(safeDays),
                "avgDuration" to avgDuration(safeDays),
                "eventRanking" to eventRanking(safeDays),
                "hourlyDistribution" to hourlyDistribution(safeDays),
                "retentionDay1" to retention(1),
                "retentionDay7" to retention(7),
            ),
        )
    }

    private fun dau(days: Int): List<Map<String, Any?>> = runCatching {
        jdbc.queryForList(
            """SELECT (created_at AT TIME ZONE '$tz')::date AS d,
                      COUNT(DISTINCT user_id) AS users,
                      COUNT(*) FILTER (WHERE event = 'app_open') AS opens
               FROM track_event
               WHERE event = 'app_open'
                 AND created_at >= now() - INTERVAL '$days days'
                 AND user_id IS NOT NULL
               GROUP BY d ORDER BY d DESC""",
        ).map { mapOf("date" to it["d"].toString(), "users" to it["users"], "opens" to it["opens"]) }
    }.getOrDefault(emptyList())

    private fun pageViews(days: Int): List<Map<String, Any?>> = runCatching {
        jdbc.queryForList(
            """SELECT props_json->>'page' AS page, COUNT(*) AS pv,
                      COUNT(DISTINCT user_id) AS uv
               FROM track_event
               WHERE event = 'page_view'
                 AND created_at >= now() - INTERVAL '$days days'
               GROUP BY page ORDER BY pv DESC""",
        ).map { mapOf("page" to (it["page"] ?: "unknown"), "pv" to it["pv"], "uv" to it["uv"]) }
    }.getOrDefault(emptyList())

    private fun avgDuration(days: Int): List<Map<String, Any?>> = runCatching {
        jdbc.queryForList(
            """SELECT props_json->>'page' AS page,
                      ROUND(AVG((props_json->>'duration')::numeric), 1) AS avg_sec,
                      PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY (props_json->>'duration')::numeric) AS median_sec,
                      COUNT(*) AS samples
               FROM track_event
               WHERE event = 'page_leave'
                 AND props_json->>'duration' IS NOT NULL
                 AND (props_json->>'duration')::numeric BETWEEN 1 AND 3600
                 AND created_at >= now() - INTERVAL '$days days'
               GROUP BY page ORDER BY avg_sec DESC""",
        ).map {
            mapOf(
                "page" to (it["page"] ?: "unknown"),
                "avgSec" to it["avg_sec"],
                "medianSec" to it["median_sec"],
                "samples" to it["samples"],
            )
        }
    }.getOrDefault(emptyList())

    private fun eventRanking(days: Int): List<Map<String, Any?>> = runCatching {
        jdbc.queryForList(
            """SELECT event, COUNT(*) AS cnt,
                      COUNT(DISTINCT user_id) AS users
               FROM track_event
               WHERE created_at >= now() - INTERVAL '$days days'
               GROUP BY event ORDER BY cnt DESC""",
        ).map { mapOf("event" to it["event"], "count" to it["cnt"], "users" to it["users"]) }
    }.getOrDefault(emptyList())

    private fun hourlyDistribution(days: Int): List<Map<String, Any?>> = runCatching {
        jdbc.queryForList(
            """SELECT EXTRACT(HOUR FROM created_at AT TIME ZONE '$tz')::int AS hour,
                      COUNT(*) AS cnt
               FROM track_event
               WHERE event = 'app_open'
                 AND created_at >= now() - INTERVAL '$days days'
               GROUP BY hour ORDER BY hour""",
        ).map { mapOf("hour" to it["hour"], "count" to it["cnt"]) }
    }.getOrDefault(emptyList())

    private fun retention(retentionDay: Int): Map<String, Any?> = runCatching {
        val row = jdbc.queryForMap(
            """WITH first_seen AS (
                 SELECT user_id, MIN((created_at AT TIME ZONE '$tz')::date) AS d0
                 FROM track_event
                 WHERE event = 'app_open' AND user_id IS NOT NULL
                 GROUP BY user_id
               ),
               cohort AS (
                 SELECT d0, COUNT(*) AS cohort_size FROM first_seen GROUP BY d0
               ),
               retained AS (
                 SELECT f.d0, COUNT(DISTINCT f.user_id) AS retained
                 FROM first_seen f
                 JOIN track_event t ON t.user_id = f.user_id
                   AND (t.created_at AT TIME ZONE '$tz')::date = f.d0 + $retentionDay
                   AND t.event = 'app_open'
                 GROUP BY f.d0
               )
               SELECT COALESCE(SUM(c.cohort_size), 0) AS total_cohort,
                      COALESCE(SUM(r.retained), 0) AS total_retained
               FROM cohort c LEFT JOIN retained r ON c.d0 = r.d0
               WHERE c.d0 >= (now() AT TIME ZONE '$tz')::date - 30
                 AND c.d0 <= (now() AT TIME ZONE '$tz')::date - $retentionDay""",
        )
        val cohort = (row["total_cohort"] as? Number)?.toLong() ?: 0
        val retained = (row["total_retained"] as? Number)?.toLong() ?: 0
        mapOf(
            "day" to retentionDay,
            "cohortSize" to cohort,
            "retained" to retained,
            "rate" to if (cohort > 0) "%.1f".format(retained * 100.0 / cohort) else "0.0",
        )
    }.getOrDefault(mapOf("day" to retentionDay, "cohortSize" to 0, "retained" to 0, "rate" to "0.0"))
}
