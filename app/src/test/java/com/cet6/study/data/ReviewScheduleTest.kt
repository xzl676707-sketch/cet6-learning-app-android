package com.cet6.study.data

import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

class ReviewScheduleTest {
    private val shanghai = ZoneId.of("Asia/Shanghai")
    private fun time(value: String) = ZonedDateTime.parse(value).toInstant().toEpochMilli()

    @Test fun lateNightIsDueAtNextMidnight() {
        assertEquals(time("2026-10-22T00:00:00+08:00"), reviewDateAfterDays(time("2026-10-21T23:50:00+08:00"), 1, shanghai))
    }
    @Test fun midnightAndMonthYearBoundaries() {
        assertEquals(time("2027-01-01T00:00:00+08:00"), reviewDateAfterDays(time("2026-12-31T00:00:00+08:00"), 1, shanghai))
        assertEquals(time("2028-02-29T00:00:00+08:00"), reviewDateAfterDays(time("2028-02-28T23:59:59+08:00"), 1, shanghai))
    }
    @Test fun calendarDaysFollowDaylightSaving() {
        val zone = ZoneId.of("America/New_York")
        assertEquals(time("2026-03-09T00:00:00-04:00"), reviewDateAfterDays(time("2026-03-08T00:00:00-05:00"), 1, zone))
        assertEquals(time("2026-11-02T00:00:00-05:00"), reviewDateAfterDays(time("2026-11-01T00:00:00-04:00"), 1, zone))
    }
    @Test fun existingDueTimesKeepTheirDate() {
        val original = time("2026-10-22T23:50:00+08:00")
        assertEquals(time("2026-10-22T00:00:00+08:00"), reviewDayStartMillis(original, shanghai))
        assertEquals(reviewDayStartMillis(original, shanghai), reviewDayStartMillis(reviewDayStartMillis(original, shanghai), shanghai))
    }
    @Test fun stagesStillAdvanceAndRetreat() {
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(shanghai))
            val now = time("2026-10-21T23:50:00+08:00")
            assertEquals(ReviewSchedule(0, 1, time("2026-10-22T00:00:00+08:00")), scheduleNextReview("熟悉", -1, now))
            assertEquals(ReviewSchedule(1, 3, time("2026-10-24T00:00:00+08:00")), scheduleNextReview("熟悉", 0, now))
            assertEquals(0, scheduleNextReview("不会", 0, now).stage)
            assertEquals(1, scheduleNextReview("不会", 2, now).stage)
            assertEquals(1, scheduleNextReview("有点了解", 2, now).stage)
            assertEquals(60, scheduleNextReview("熟悉", 5, now).intervalDays)
        } finally { TimeZone.setDefault(previous) }
    }
}
