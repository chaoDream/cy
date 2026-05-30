package com.zdsj.watch

import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional

interface WatchItemRepository : JpaRepository<WatchItem, Long> {
    fun findByUserIdOrderByCreatedAtDesc(userId: Long): List<WatchItem>
    fun findByUserIdAndRawProductId(userId: Long, rawProductId: Long): Optional<WatchItem>
    fun findByPollTierAndStatus(pollTier: String, status: String): List<WatchItem>
}

interface AlertHitRecordRepository : JpaRepository<AlertHitRecord, Long> {
    fun findByWatchItemIdOrderByNotifiedAtDesc(watchItemId: Long): List<AlertHitRecord>
}
