package com.teslalink.app.vm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「接续上一段路程」的纯逻辑用例：时间窗、不能连着重接更早的段、合并后起点/里程/峰值口径。
 */
class TripResumeTest {

    private val now = 1_800_000_000_000L

    private fun trip(start: Long, end: Long, odoStart: Int? = null, odoEnd: Int? = null, peak: Float? = null) =
        Trip(
            startedAt = start,
            endedAt = end,
            points = MutableList(3) { TripPoint(start + it, 30.0, 120.0, null, 40f) },
            startOdoHm = odoStart,
            endOdoHm = odoEnd,
            peakKmh = peak,
        )

    @Test
    fun previousTripJustEndedIsOffered() {
        val prev = trip(now - 30 * 60_000L, now - 10 * 60_000L)
        assertSame(prev, pickResumable(listOf(prev), open = null, now = now))
    }

    @Test
    fun previousTripOlderThanWindowIsNotOffered() {
        val prev = trip(now - 3 * 3_600_000L, now - 61 * 60_000L)
        assertNull(pickResumable(listOf(prev), open = null, now = now))
    }

    @Test
    fun dismissedTripStaysHidden() {
        val prev = trip(now - 30 * 60_000L, now - 10 * 60_000L)
        assertNull(pickResumable(listOf(prev), open = null, now = now, dismissedStart = prev.startedAt))
    }

    @Test
    fun alreadyContinuedOpenTripStopsTheOffer() {
        val prev = trip(now - 2 * 3_600_000L, now - 90 * 60_000L)
        val cur = trip(now - 20 * 60_000L, 0L).apply { continued = true }
        assertNull(pickResumable(listOf(prev), open = cur, now = now))
    }

    @Test
    fun tripWithoutEnoughPointsIsNotOffered() {
        val prev = trip(now - 30 * 60_000L, now - 10 * 60_000L).apply { points.clear() }
        assertNull(pickResumable(listOf(prev), open = null, now = now))
    }

    @Test
    fun mergePrependsPreviousPointsAndKeepsOrigin() {
        val prev = trip(now - 2 * 3_600_000L, now - 60 * 60_000L, odoStart = 1000, odoEnd = 1600, peak = 110f)
        val cur = trip(now - 10 * 60_000L, 0L, odoStart = 1602, odoEnd = 1650, peak = 95f)
        val merged = mergePreviousIntoOpen(prev, cur)

        assertSame(cur, merged)
        assertEquals(prev.startedAt, merged.startedAt)
        assertEquals(6, merged.points.size)
        assertEquals(prev.points.first(), merged.points.first())
        assertEquals(1000, merged.startOdoHm)
        assertEquals(1650, merged.endOdoHm)
        assertEquals(110f, merged.peakKmh!!, 0f)
        assertTrue(merged.continued)
        // 口径：起点到终点横跨休息时间，里程只算两段车轮里程差（1650-1000 格 ≈ 10.46 km）。
        assertEquals(650 * 0.0160934, merged.distanceKm, 1e-6)
        assertEquals(cur.points.last().t - prev.startedAt, merged.durationMs)
    }

    @Test
    fun mergeFallsBackToOpenOdometerWhenPreviousLacksIt() {
        val prev = trip(now - 90 * 60_000L, now - 50 * 60_000L)
        val cur = trip(now - 5 * 60_000L, 0L, odoStart = 2000, odoEnd = 2010)
        val merged = mergePreviousIntoOpen(prev, cur)
        assertEquals(2000, merged.startOdoHm)
        assertEquals(2010, merged.endOdoHm)
    }
}
