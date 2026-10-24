package com.teslalink.app.vm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 轨迹漂移清洗的回归用例：既要删掉"甩出去再拉回来"的刺头，又不能删掉真实弯道。
 * 坐标用一个 30°N 附近的局部平面换算，每米对应的经纬度足够精确。
 */
class TripDespikeTest {

    private val mLat = 1.0 / 111_320.0
    private val mLng = 1.0 / (111_320.0 * Math.cos(Math.toRadians(30.0)))

    /** east/north 单位：米；t 单位：秒。 */
    private fun pt(t: Long, east: Double, north: Double, kmh: Float = 30f) =
        TripPoint(t * 1000, 30.0 + north * mLat, 120.0 + east * mLng, null, kmh)

    /** 30 km/h ≈ 83.3m/10s。 */
    private val leg = 83.3

    @Test
    fun straightPathKept() {
        val pts = (0..8).map { pt(it * 10L, it * leg, 0.0) }
        assertEquals(pts.size, dropJumps(pts).size)
    }

    @Test
    fun singleSidetrackDropped() {
        val pts = listOf(
            pt(0, 0.0, 0.0),
            pt(10, leg, 0.0),
            pt(20, 2 * leg, 0.0),
            pt(30, 3 * leg, 300.0), // 横向甩出去 300m
            pt(40, 4 * leg, 0.0),
            pt(50, 5 * leg, 0.0),
        )
        val out = dropJumps(pts)
        assertFalse(out.any { it.lat > 30.0 + 100 * mLat })
        assertTrue(out.size >= 4)
    }

    @Test
    fun twoPointDriftPlateauDropped() {
        val pts = listOf(
            pt(0, 0.0, 0.0),
            pt(10, leg, 0.0),
            pt(20, 2 * leg, 300.0),
            pt(30, 2 * leg + leg, 300.0),
            pt(40, 4 * leg, 0.0),
            pt(50, 5 * leg, 0.0),
        )
        val out = dropJumps(pts)
        assertFalse(out.any { it.lat > 30.0 + 100 * mLat })
    }

    @Test
    fun firstPointDriftDropped() {
        val pts = listOf(
            pt(0, 0.0, 300.0),
            pt(10, 0.0, 0.0),
            pt(20, leg, 0.0),
            pt(30, 2 * leg, 0.0),
        )
        val out = dropJumps(pts)
        assertFalse(out.any { it.lat > 30.0 + 100 * mLat })
    }

    @Test
    fun genuineRightAngleTurnKept() {
        // 60 km/h：167m 一腿，路口直角转弯的量级
        val l = 166.7
        val pts = listOf(
            pt(0, 0.0, 0.0, 60f),
            pt(10, l, 0.0, 60f),
            pt(20, 2 * l, 0.0, 60f),
            pt(30, 2 * l, l, 60f),
            pt(40, 2 * l, 2 * l, 60f),
        )
        assertEquals(pts.size, dropJumps(pts).size)
    }

    @Test
    fun parkedDriftSpikeStillDropped() {
        // 驻车漂移：车只在原地蠕行，中间一点甩出去 100m。速度≈0 → 路程护栏里 60m 兜底
        // 仍然判为刺头；护栏保护的是"两腿与车速相称"的点，不是这种。
        val pts = listOf(
            pt(0, 0.0, 0.0, 0f),
            pt(10, 3.0, 0.0, 0f),
            pt(20, 100.0, 0.0, 0f),
            pt(30, 6.0, 0.0, 0f),
            pt(40, 9.0, 0.0, 0f),
        )
        val out = dropJumps(pts)
        assertFalse(out.any { it.lng > 120.0 + 50 * mLng })
        assertEquals(4, out.size)
    }

    @Test
    fun sidewaysExcursionWhileMovingStillDropped() {
        // 行驶中横向甩刺 200m（帐篷形：A、B 从顶点看去同向）：判据2 角度门通过后，
        // 速度护栏必删——30km/h 十秒只跑得出 83m，两腿之和 433m 远超。
        // （80m 级别的"鼓包"几何上是合法弯道，本来就该保留。）
        val pts = listOf(
            pt(0, 0.0, 0.0, 30f),
            pt(10, leg, 200.0, 30f),
            pt(20, 2 * leg, 0.0, 30f),
        )
        val out = dropJumps(pts)
        assertFalse(out.any { it.lat > 30.0 + 100 * mLat })
        assertEquals(2, out.size)
    }

    @Test
    fun bandsIndexAndNormalize() {
        val b = SpeedBands()
        assertEquals(0, b.bandIndex(65f))
        assertEquals(1, b.bandIndex(66f))
        assertEquals(3, b.bandIndex(131f))
        assertEquals(4, b.bandIndex(132f))

        val decoded = SpeedBands.decode("50,70,90,110|4,3,2,1,0")
        assertEquals(listOf(50, 70, 90, 110), decoded.breaks)
        assertEquals(listOf(4, 3, 2, 1, 0), decoded.colors)

        // 乱序/越界的配置必须被收成严格递增且合法
        val fixed = SpeedBands(listOf(500, 50, 60, 70), listOf(99, -1)).normalized()
        assertTrue(fixed.breaks.zipWithNext().all { (a, c) -> c >= a + 1 })
        assertTrue(fixed.breaks.last() <= 240)
        assertEquals(5, fixed.colors.size)
    }

    @Test
    fun decodeGarbageFallsBackToDefaults() {
        assertEquals(SpeedBands.DEFAULT_BREAKS, SpeedBands.decode("nonsense").breaks)
        assertEquals(SpeedBands.DEFAULT_COLORS, SpeedBands.decode(null).colors)
    }
}
