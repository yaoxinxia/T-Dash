package com.teslalink.app.vm

import com.tesla.generated.carserver.common.Common
import com.tesla.generated.carserver.vehicle.Vehicle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Shared wire-format fixture for the two telemetry view models. */
internal fun driveFrame(speedMph: Float? = null, shift: String? = null, power: Int? = null): Vehicle.VehicleData {
    val drive = Vehicle.DriveState.newBuilder()
    speedMph?.let { drive.setSpeedFloat(it) }
    power?.let { drive.setPower(it) }
    shift?.let {
        val value = Common.Void.getDefaultInstance()
        val state = Vehicle.ShiftState.newBuilder()
        when (it) {
            "P" -> state.setP(value)
            "N" -> state.setN(value)
            "D" -> state.setD(value)
            "R" -> state.setR(value)
            else -> state.setInvalid(value)
        }
        drive.setShiftState(state)
    }
    return Vehicle.VehicleData.newBuilder().setDriveState(drive).build()
}

class DashboardViewModelTest {
    private val now = 100_000L

    @Test
    fun parkToDriveUsesNewFrameImmediately() {
        val previous = DashboardViewModel.ClusterState(speedKmh = 0f, shift = "P", powerKw = 0)
        val frame = driveFrame(12.5f, "D", 80)
        val sample = parseDriveSample(frame)
        val applied = applyDashboardDrive(previous, frame, now)
        val until = nextDrivingUntil(0L, sample, now)

        assertEquals("D", applied.shift)
        assertEquals(12.5f * 1.609344f, applied.speedKmh, 0.0001f)
        assertEquals(80, applied.powerKw)
        assertEquals(now, applied.updatedAtMs)
        assertEquals(now + 90_000L, until)
        assertFalse(DriveReadings().updated(sample, now).allowsSlowRead(now, until))
    }

    @Test
    fun driveAndReverseAtZeroSpeedLatchAndBlockSlowReads() {
        for (shift in listOf("D", "R")) {
            val sample = parseDriveSample(driveFrame(0f, shift))
            val readings = DriveReadings().updated(sample, now)
            assertTrue(readings.moving(now))
            assertEquals(now + 90_000L, nextDrivingUntil(0L, sample, now))
            // Even without a latch, a fresh D/R must never authorize slow reads.
            assertFalse(readings.allowsSlowRead(now, 0L))
        }
    }

    @Test
    fun onlyFreshLowSpeedParkOrNeutralAllowsSlowReads() {
        for (shift in listOf("P", "N")) {
            val sample = parseDriveSample(driveFrame(0f, shift))
            val readings = DriveReadings().updated(sample, now)
            assertTrue(readings.allowsSlowRead(now, 0L))
            assertFalse(readings.allowsSlowRead(now, now + 90_000L))
            assertTrue(readings.allowsSlowRead(now + 4_999L, 0L))
            assertFalse(readings.allowsSlowRead(now + 5_000L, 0L))
            assertFalse(readings.allowsSlowRead(now - 1L, 0L))
            assertEquals(0L, nextDrivingUntil(now + 90_000L, sample, now))
        }
        assertFalse(DriveReadings().allowsSlowRead(now, 0L))
        assertFalse(DriveReadings().updated(DriveSample(1f, "P"), now).allowsSlowRead(now, 0L))
        assertFalse(DriveReadings().updated(DriveSample(-10f, "P"), now).allowsSlowRead(now, 0L))
    }

    @Test
    fun missingAndEmptyDriveDoNotRenewOldValuesOrLatch() {
        val old = DashboardViewModel.ClusterState(speedKmh = 80f, shift = "D", updatedAtMs = now)
        val oldReadings = DriveReadings().updated(DriveSample(80f, "D"), now)
        val until = now + 90_000L
        val frames = listOf(
            null,
            Vehicle.VehicleData.getDefaultInstance(),
            Vehicle.VehicleData.newBuilder()
                .setChargeState(Vehicle.ChargeState.newBuilder().setBatteryLevel(75)).build(),
            driveFrame(),
            driveFrame(shift = "invalid"),
        )
        for (frame in frames) {
            val sample = parseDriveSample(frame)
            assertEquals(DriveSample(), sample)
            assertEquals(old, applyDashboardDrive(old, frame, now + 6_000L))
            assertEquals(oldReadings, oldReadings.updated(sample, now + 6_000L))
            assertEquals(until, nextDrivingUntil(until, sample, now + 6_000L))
            assertFalse(oldReadings.updated(sample, now + 6_000L).moving(now + 6_000L))
            assertFalse(oldReadings.allowsSlowRead(until + 1L, until))
        }
    }

    @Test
    fun powerOnlyDriveRetainsSpeedClockAndDoesNotExtendLatch() {
        val previous = DashboardViewModel.ClusterState(speedKmh = 80f, shift = "D", updatedAtMs = now)
        val frame = driveFrame(power = 150)
        val next = applyDashboardDrive(previous, frame, now + 10_000L)
        assertEquals(150, next.powerKw)
        assertEquals(previous.speedKmh, next.speedKmh, 0f)
        assertEquals(now, next.updatedAtMs)
        assertEquals(now + 90_000L, nextDrivingUntil(now + 90_000L, parseDriveSample(frame), now + 10_000L))
    }

    @Test
    fun speedOnlyZeroFrameCannotReuseOldDriveShiftToExtendLatch() {
        val sample = parseDriveSample(driveFrame(0f))
        val readings = DriveReadings().updated(DriveSample(0f, "D"), now).updated(sample, now + 10_000L)
        assertEquals(now, readings.shiftAtMs)
        assertEquals(now + 10_000L, readings.speedAtMs)
        assertEquals(now + 90_000L, nextDrivingUntil(now + 90_000L, sample, now + 10_000L))
        assertEquals(now + 100_000L, nextDrivingUntil(now + 90_000L, DriveSample(shift = "D"), now + 10_000L))
    }

    @Test
    fun partialFramesCannotFreshenBothParkingFieldsOrClearLatch() {
        val parked = DriveReadings().updated(DriveSample(0f, "P"), now)
        val speedOnly = parked.updated(parseDriveSample(driveFrame(0f)), now + 6_000L)
        val shiftOnly = parked.updated(parseDriveSample(driveFrame(shift = "P")), now + 6_000L)
        assertFalse(speedOnly.allowsSlowRead(now + 6_000L, 0L))
        assertFalse(shiftOnly.allowsSlowRead(now + 6_000L, 0L))
        assertEquals(now + 90_000L, nextDrivingUntil(now + 90_000L, DriveSample(shift = "P"), now + 6_000L))
    }

    @Test
    fun movingSpeedWithoutShiftIsStillNewDrivingEvidence() {
        val sample = parseDriveSample(driveFrame(15f))
        assertEquals(now + 90_000L, nextDrivingUntil(0L, sample, now))
        assertFalse(DriveReadings().updated(sample, now).allowsSlowRead(now, 0L))
    }

    @Test
    fun shiftOnlyFrameUpdatesGearWithoutRefreshingCachedSpeed() {
        val previous = DashboardViewModel.ClusterState(speedKmh = 60f, shift = "P", updatedAtMs = now)
        val frame = driveFrame(shift = "D")
        val next = applyDashboardDrive(previous, frame, now + 6_000L)
        assertEquals("D", next.shift)
        assertEquals(previous.speedKmh, next.speedKmh, 0f)
        assertEquals(previous.updatedAtMs, next.updatedAtMs)
        assertNull(parseDriveSample(frame).speedKmh)
        assertEquals(now + 96_000L, nextDrivingUntil(0L, parseDriveSample(frame), now + 6_000L))
    }

    @Test
    fun floatSpeedWinsOverIntegerAndInvalidSpeedIsNotEvidence() {
        val frame = Vehicle.VehicleData.newBuilder().setDriveState(
            Vehicle.DriveState.newBuilder().setSpeed(12).setSpeedFloat(12.5f),
        ).build()
        assertEquals(12.5f * 1.609344f, parseDriveSample(frame).speedKmh!!, 0.0001f)
        val integerOnly = Vehicle.VehicleData.newBuilder().setDriveState(
            Vehicle.DriveState.newBuilder().setSpeed(12),
        ).build()
        assertEquals(12f * 1.609344f, parseDriveSample(integerOnly).speedKmh!!, 0.0001f)
        assertNull(parseDriveSample(driveFrame(Float.NaN)).speedKmh)
        assertNull(parseDriveSample(driveFrame(Float.POSITIVE_INFINITY)).speedKmh)
    }

    @Test
    fun parkedPaceStaysAtOrAboveOneHzRegardlessOfHeadUnitSession() {
        // 驻车：固定 600ms，且不接受"车机没会话就降到 5 秒"那种掉频。
        assertTrue(fastLaneInterval(moving = false, lastRttMs = 0L) <= 1_000L)
        assertTrue(fastLaneInterval(moving = false, lastRttMs = 20_000L) <= 1_000L)
        // 行驶：跟着真实往返，250–500ms。
        assertEquals(250L, fastLaneInterval(moving = true, lastRttMs = 0L))
        assertEquals(250L, fastLaneInterval(moving = true, lastRttMs = 120L))
        assertEquals(500L, fastLaneInterval(moving = true, lastRttMs = 4_000L))
        // 驻车一次读的总预算必须 ≤1 秒：车机睡着时预算就是实际节奏，2.5s 预算等于 0.4Hz。
        assertTrue(fastLaneBudgetMs(moving = false) <= 1_000L)
        // 别的请求都得比车速读的驻车上限更短，才能插在两次车速轮询中间发。
        assertTrue(SLOW_READ_BUDGET_MS <= fastLaneBudgetMs(moving = false))
    }

    @Test
    fun parkedReadWithNoSessionStillCompletesWithinOneSecond() {
        // 纯预算断言：车机域握手最快也要 8 秒，驻车读不能靠"等它答"来定节奏，必须靠自己的预算截断。
        val parkedBudget = fastLaneBudgetMs(moving = false)
        assertEquals(1_000L, parkedBudget)
        assertEquals(2_500L, fastLaneBudgetMs(moving = true))
    }
}
