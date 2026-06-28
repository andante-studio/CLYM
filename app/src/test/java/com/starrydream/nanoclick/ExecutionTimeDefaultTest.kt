package com.starrydream.nanoclick

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionTimeDefaultTest {
    @Test
    fun defaultTimeUsesCurrentHourForGeneralTimes() {
        assertExecutionTime("2026-06-17T02:28:15.000", "02", "59", "59", "960")
    }

    @Test
    fun defaultTimeUsesCurrentHourNearEndOfHourWhenStillFuture() {
        assertExecutionTime("2026-06-17T02:59:50.000", "02", "59", "59", "960")
        assertExecutionTime("2026-06-17T02:59:59.959", "02", "59", "59", "960")
    }

    @Test
    fun defaultTimeMovesToNextHourWhenCandidateIsNowOrPast() {
        assertExecutionTime("2026-06-17T02:59:59.960", "03", "59", "59", "960")
        assertExecutionTime("2026-06-17T02:59:59.980", "03", "59", "59", "960")
    }

    @Test
    fun defaultTimeHandlesMidnightBoundary() {
        assertExecutionTime("2026-06-17T23:30:00.000", "23", "59", "59", "960")
        assertExecutionTime("2026-06-17T23:59:59.980", "00", "59", "59", "960")
        assertExecutionTime("2026-06-18T00:00:00.000", "00", "59", "59", "960")
    }

    @Test
    fun scheduleTargetUsesServerOffsetForFutureTime() {
        val serverNowMs = LocalDateTime.parse("2026-06-17T13:00:01.000")
            .toInstant(ZoneOffset.UTC)
            .toEpochMilli()
        val serverOffsetMs = 2_000L
        val target = calculateScheduleTarget(
            hour = "13",
            minute = "00",
            second = "05",
            millisecond = "000",
            deviceNowMs = serverNowMs - serverOffsetMs,
            serverOffsetMs = serverOffsetMs,
            elapsedNowMs = 50_000L,
            zoneId = ZoneId.of("UTC")
        ).getOrThrow()

        assertEquals(54_000L, target.targetElapsedMs)
        assertEquals("13:00:05.000", target.label)
    }

    @Test
    fun scheduleTargetAllowsDeviceTimeWithZeroOffset() {
        val deviceNowMs = LocalDateTime.parse("2026-06-17T13:00:01.000")
            .toInstant(ZoneOffset.UTC)
            .toEpochMilli()
        val target = calculateScheduleTarget(
            hour = "13",
            minute = "00",
            second = "06",
            millisecond = "500",
            deviceNowMs = deviceNowMs,
            serverOffsetMs = 0L,
            elapsedNowMs = 100_000L,
            zoneId = ZoneId.of("UTC")
        ).getOrThrow()

        assertEquals(105_500L, target.targetElapsedMs)
        assertEquals("13:00:06.500", target.label)
    }

    @Test
    fun scheduleTargetRejectsPastServerTime() {
        val serverNowMs = LocalDateTime.parse("2026-06-17T00:16:42.000")
            .toInstant(ZoneOffset.UTC)
            .toEpochMilli()
        val serverOffsetMs = 2_000L
        val result = calculateScheduleTarget(
            hour = "00",
            minute = "16",
            second = "41",
            millisecond = "000",
            deviceNowMs = serverNowMs - serverOffsetMs,
            serverOffsetMs = serverOffsetMs,
            elapsedNowMs = 50_000L,
            zoneId = ZoneId.of("UTC")
        )

        assertTrue(result.isFailure)
        assertEquals(
            "이미 지난 시각입니다. 실행 시각을 다시 설정해주세요.",
            result.exceptionOrNull()?.message
        )
    }

    private fun assertExecutionTime(
        nowText: String,
        hour: String,
        minute: String,
        second: String,
        millisecond: String
    ) {
        val actual = calculateDefaultExecutionTime(LocalDateTime.parse(nowText))

        assertEquals(hour, actual.hour)
        assertEquals(minute, actual.minute)
        assertEquals(second, actual.second)
        assertEquals(millisecond, actual.millisecond)
    }
}
