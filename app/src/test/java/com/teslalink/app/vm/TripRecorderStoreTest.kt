package com.teslalink.app.vm

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 轨迹落盘/加载的回归用例（纯 JVM，无 Android 依赖）：
 * - 停车中重启时，"没收尾但已超 3 分钟"的行程要就地收尾，不能整场挂"进行中"；
 * - 3 分钟内的才恢复成 open 继续写；
 * - 截断 JSON（写一半被杀）不能带走启动，也不能让别的行程消失。
 */
class TripRecorderStoreTest {

    /** 临时 filesDir 根；TripRecorder 自己会在下面建 trips/。 */
    private fun newBase(): File = createTempDir("trips-test")

    private fun tripsDirOf(base: File): File = base.resolve("trips").apply { mkdirs() }

    private fun tripJson(
        startedAt: Long,
        endedAt: Long,
        points: List<Triple<Long, Double, Double>>,
    ): String {
        val arr = JSONArray()
        points.forEach { (t, lat, lng) ->
            arr.put(JSONObject().put("t", t).put("lat", lat).put("lng", lng).put("h", -1).put("v", 30.0))
        }
        return JSONObject()
            .put("startedAt", startedAt)
            .put("endedAt", if (endedAt == 0L) JSONObject.NULL else endedAt)
            .put("startOdoHm", JSONObject.NULL)
            .put("endOdoHm", JSONObject.NULL)
            .put("peakKmh", JSONObject.NULL)
            .put("continued", false)
            .put("points", arr)
            .toString()
    }

    @Test
    fun staleOpenTripIsFinishedOnLoad() {
        val base = newBase()
        val dir = tripsDirOf(base)
        val now = System.currentTimeMillis()
        val started = now - 60 * 60_000L
        val last = now - 10 * 60_000L   // 停车已 10 分钟（> PARK_TO_END_MS=3min，< GAP=6h）
        File(dir, "trip_$started.json").writeText(
            tripJson(started, 0L, listOf(Triple(started, 30.0, 120.0), Triple(last, 30.001, 120.0))),
        )

        val r = TripRecorder(base, syncLoad = true)
        assertFalse("超时未收尾的行程不该恢复成进行中", r.hasOpenTrip())
        val trips = r.trips.value
        assertEquals(1, trips.size)
        assertEquals("应按收尾规则就地结束在最后一个采样点", last, trips[0].endedAt)
    }

    @Test
    fun recentOpenTripIsResumedOnLoad() {
        val base = newBase()
        val dir = tripsDirOf(base)
        val now = System.currentTimeMillis()
        val started = now - 30 * 60_000L
        val last = now - 60_000L   // 最后一点在 1 分钟前（< 3min）→ 还在录
        File(dir, "trip_$started.json").writeText(
            tripJson(started, 0L, listOf(Triple(started, 30.0, 120.0), Triple(last, 30.001, 120.0))),
        )

        val r = TripRecorder(base, syncLoad = true)
        assertTrue("3 分钟内的未收尾行程应恢复成进行中", r.hasOpenTrip())
        assertEquals(0L, r.trips.value.first { it.startedAt == started }.endedAt)
    }

    @Test
    fun truncatedJsonDoesNotKillLoad() {
        val base = newBase()
        val dir = tripsDirOf(base)
        val now = System.currentTimeMillis()
        val good = now - 90 * 60_000L
        File(dir, "trip_${good}.json").writeText(
            tripJson(good, good + 20 * 60_000L, listOf(Triple(good, 30.0, 120.0), Triple(good + 20 * 60_000L, 30.001, 120.0))),
        )
        // 模拟"写一半被杀"：合法 JSON 的前半截。
        File(dir, "trip_${now - 5 * 60_000L}.json").writeText(
            tripJson(now - 5 * 60_000L, 0L, listOf(Triple(now - 5 * 60_000L, 30.0, 120.0)))
                .substring(0, 40),
        )

        val r = TripRecorder(base, syncLoad = true)
        assertEquals("坏文件跳过，好行程必须还在", 1, r.trips.value.size)
    }

    @Test
    fun atomicPersistLeavesNoTmpBehind() {
        val base = newBase()
        val dir = tripsDirOf(base)
        val r = TripRecorder(base, syncLoad = true)
        val now = System.currentTimeMillis()
        r.add(TripPoint(now, 30.0, 120.0, null, 0f))
        r.add(TripPoint(now + 1_000, 30.0001, 120.0, null, 0f))
        r.finish(now + 2_000, null)
        // 落盘走 IO 队列：轮询等它写完（单测里没有别的办法等队列）。
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline &&
            !dir.resolve("trip_${now}.json").exists()
        ) Thread.sleep(20)
        assertTrue(dir.resolve("trip_${now}.json").exists())
        assertEquals(0, dir.listFiles { f -> f.name.endsWith(".tmp") }?.size ?: -1)
    }
}
