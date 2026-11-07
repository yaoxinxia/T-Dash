package com.teslalink.app.vm

import com.tesla.generated.carserver.vehicle.Vehicle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 车机导航行程的解析判据。这些字段一直在 DRIVE 帧里，之前没解析；
 * 现在上了仪表，就必须钉住"什么算在导航、什么算过期"，否则假目的地会常驻在仪表顶部。
 */
class NavInfoTest {

    private fun drive(block: Vehicle.DriveState.Builder.() -> Unit): Vehicle.DriveState =
        Vehicle.DriveState.newBuilder().apply(block).build()

    @Test
    fun `空帧不给时间戳——没在导航时界面一行都不画`() {
        val nav = parseNav(drive { }, 1_000L)
        assertNull(nav.destination)
        assertNull(nav.minutes)
        assertNull(nav.atMs)
        assertFalse(navIsFresh(nav.atMs, 1_000L))
    }

    @Test
    fun `目的地与剩余时间是真证据，残缺的电量帧不算`() {
        val withDest = parseNav(
            drive { setActiveRouteDestination("  人民路 100 号 ") },
            5_000L,
        )
        assertEquals("人民路 100 号", withDest.destination)
        assertEquals(5_000L, withDest.atMs)

        // energy_at_arrival 单独出现（没有目的地也没有剩余时间）说明这帧残缺，不点亮界面。
        val partial = parseNav(
            drive { setActiveRouteEnergyAtArrival(43f) },
            5_000L,
        )
        assertEquals(43, partial.arrivalSoc)
        assertNull(partial.atMs)
    }

    @Test
    fun `英里转公里、非有限值一律丢掉`() {
        val nav = parseNav(
            drive {
                setActiveRouteMinutesToArrival(12f)
                setActiveRouteMilesToArrival(5f)
            },
            1L,
        )
        assertEquals(8.04672f, nav.kmLeft!!, 0.001f)

        val nan = parseNav(
            drive {
                setActiveRouteMinutesToArrival(Float.NaN)
                setActiveRouteMilesToArrival(Float.POSITIVE_INFINITY)
            },
            1L,
        )
        assertNull(nan.minutes)
        assertNull(nan.kmLeft)
        // 两个数都不可用 = 这一帧没有真证据，不盖时钟。
        assertNull(nan.atMs)
    }

    @Test
    fun `到达电量钳在零到一百 车机给离谱值也不外泄`() {
        val nav = parseNav(
            drive {
                setActiveRouteMinutesToArrival(1f)
                setActiveRouteEnergyAtArrival(137.4f)
            },
            1L,
        )
        assertEquals(100, nav.arrivalSoc)
    }

    @Test
    fun `25 秒窗口到点自动消失 未来时间戳也不当新鲜`() {
        assertTrue(navIsFresh(10_000L, 10_000L + NAV_STALE_MS - 1))
        assertFalse(navIsFresh(10_000L, 10_000L + NAV_STALE_MS))
        assertFalse(navIsFresh(9_000L, 1_000L))
        assertFalse(navIsFresh(null, 1_000L))
    }

    @Test
    fun `旧值跟着走但时钟不续——宽限期内不闪 超过宽限整组撤下`() {
        val first = parseNav(drive { setActiveRouteDestination("机场"); setActiveRouteMinutesToArrival(30f) }, 1_000L)
        val prev = DashboardViewModel.ClusterState(
            navDestination = first.destination,
            navMinutes = first.minutes,
            navAtMs = first.atMs,
        )
        // 车机停止导航：DRIVE 帧还在（速度有值），但路线字段整组缺席。
        val data = Vehicle.VehicleData.newBuilder()
            .setDriveState(Vehicle.DriveState.newBuilder().setSpeed(36))
            .build()
        // 宽限期内：旧值还挂着（防止单帧丢字段把导航条闪掉），但时钟不续。
        val within = applyDashboardDrive(prev, data, 1_000L + NAV_END_GRACE_MS - 1)
        assertEquals("机场", within.navDestination)
        assertEquals(1_000L, within.navAtMs)
        // 连续缺席超过宽限：判定导航结束，整组连同时钟一起清掉，界面立刻撤下。
        val after = applyDashboardDrive(within, data, 1_000L + NAV_END_GRACE_MS)
        assertNull(after.navDestination)
        assertNull(after.navMinutes)
        assertNull(after.navAtMs)
        assertFalse(navIsFresh(after.navAtMs, 1_000L + NAV_END_GRACE_MS))
    }

    @Test
    fun `导航中每一帧都续时钟 不会误判成结束`() {
        val first = parseNav(drive { setActiveRouteDestination("公司"); setActiveRouteMinutesToArrival(45f) }, 1_000L)
        val prev = DashboardViewModel.ClusterState(
            navDestination = first.destination,
            navMinutes = first.minutes,
            navAtMs = first.atMs,
        )
        val withRoute = Vehicle.VehicleData.newBuilder()
            .setDriveState(
                Vehicle.DriveState.newBuilder()
                    .setSpeed(60)
                    .setActiveRouteDestination("公司")
                    .setActiveRouteMinutesToArrival(44f)
            )
            .build()
        val next = applyDashboardDrive(prev, withRoute, 2_000L)
        assertEquals("公司", next.navDestination)
        assertEquals(44f, next.navMinutes!!, 0.01f)
        assertEquals(2_000L, next.navAtMs)
    }

    @Test
    fun `宽限判据：没导航过不算结束 未到点也不算结束`() {
        assertFalse(navEnded(null, 10_000L))
        assertFalse(navEnded(10_000L, 10_000L + NAV_END_GRACE_MS - 1))
        assertTrue(navEnded(10_000L, 10_000L + NAV_END_GRACE_MS))
    }
}
