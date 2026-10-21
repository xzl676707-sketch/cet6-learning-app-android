package com.cet6.study.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class ReviewSchedule(val stage: Int, val intervalDays: Int, val nextReviewMillis: Long)

fun scheduleNextReview(familiarity: String, currentStage: Int = -1, reviewedAt: Long = System.currentTimeMillis()): ReviewSchedule {
    val intervals = listOf(1, 3, 7, 15, 30, 60)
    val safeStage = currentStage.coerceIn(-1, intervals.lastIndex)
    val nextStage = when (familiarity) {
        "熟悉" -> (safeStage + 1).coerceIn(0, intervals.lastIndex)
        "了解", "有点了解", "不会" -> (safeStage - 1).coerceAtLeast(0)
        else -> 0
    }
    val days = intervals[nextStage]
    return ReviewSchedule(nextStage, days, reviewDateAfterDays(reviewedAt, days))
}
fun todayStartMillis(): Long = LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()


/** A calendar day follows the phone timezone, including daylight-saving transitions. */
fun reviewDateAfterDays(reviewedAt: Long, days: Int, zone: ZoneId = ZoneId.systemDefault()): Long =
    Instant.ofEpochMilli(reviewedAt).atZone(zone).toLocalDate().plusDays(days.toLong())
        .atStartOfDay(zone).toInstant().toEpochMilli()

fun reviewDayStartMillis(timestamp: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
    reviewDateAfterDays(timestamp, 0, zone)
