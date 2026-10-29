package com.teslalink.app.vm

import com.tesla.generated.carserver.vehicle.Vehicle
import com.teslalink.app.vehicle.Payloads
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StatusViewModelTest {
    private val now = 100_000L
    private val slowCategories = listOf(
        Payloads.Category.CLOSURES,
        Payloads.Category.CLIMATE,
        Payloads.Category.CHARGE,
        Payloads.Category.LOCATION,
    )

    @Test
    fun unknownOrExpiredDriveOnlyPermitsDriveProbeNotSlowCategories() {
        val stale = applyStatusDrive(StatusSnapshot(), driveFrame(0f, "P"), now - 5_000L)
        for (snapshot in listOf(null, StatusSnapshot(), stale)) {
            assertTrue(statusCategoryAllowed(Payloads.Category.DRIVE, false, 0L, snapshot, now))
            for (category in slowCategories) {
                assertFalse(statusCategoryAllowed(category, false, 0L, snapshot, now))
            }
        }
    }

    @Test
    fun parkToStationaryDriveOrReverseBlocksRemainingClassesAndVcsec() {
        for (shift in listOf("D", "R")) {
            val parked = applyStatusDrive(StatusSnapshot(), driveFrame(0f, "P"), now)
            val frame = driveFrame(0f, shift)
            val next = applyStatusDrive(parked, frame, now + 100L)
            val until = nextDrivingUntil(0L, parseDriveSample(frame), now + 100L)
            assertEquals(shift, next.shiftState)
            assertTrue(next.recentlyMoving(now + 100L))
            for (category in slowCategories + Payloads.Category.DRIVE) {
                assertFalse(statusCategoryAllowed(category, false, until, next, now + 100L))
            }
            assertTrue(statusReadsBlocked(false, until, next, now + 100L))
            // Own fresh shift evidence suffices even before the shared latch is published.
            assertTrue(statusReadsBlocked(false, 0L, next, now + 100L))
        }
    }

    @Test
    fun latchStillBlocksWithZeroSpeedOldParkSnapshotAndNoVehicleMoving() {
        val snapshot = applyStatusDrive(StatusSnapshot(), driveFrame(0f, "P"), now)
        val until = now + 90_000L
        for (at in listOf(now, now + 60_001L, until - 1L)) {
            assertTrue(statusReadsBlocked(false, until, snapshot, at))
            for (category in slowCategories + Payloads.Category.DRIVE) {
                assertFalse(statusCategoryAllowed(category, false, until, snapshot, at))
            }
        }
        assertFalse(statusReadsBlocked(false, until, snapshot, until))
        // Expiring the latch does not make old parking evidence fresh again.
        assertFalse(statusCategoryAllowed(Payloads.Category.CHARGE, false, until, snapshot, until))
        assertTrue(statusCategoryAllowed(Payloads.Category.DRIVE, false, until, snapshot, until))
    }

    @Test
    fun neutralAtZeroSpeedAllowsReadsButRetainsControlButtonSafety() {
        val snapshot = applyStatusDrive(StatusSnapshot(), driveFrame(0f, "N"), now)
        assertTrue(snapshot.recentlyMoving(now))
        assertFalse(statusReadsBlocked(false, 0L, snapshot, now))
        for (category in slowCategories) {
            assertTrue(statusCategoryAllowed(category, false, 0L, snapshot, now))
        }
    }

    @Test
    fun nonDriveAndEmptyFramesRetainUiWithoutRenewingDriveClocks() {
        val previous = applyStatusDrive(
            StatusSnapshot(batteryLevel = 75, insideTemp = 21f), driveFrame(50f, "D"), now,
        )
        for (frame in listOf(
            Vehicle.VehicleData.getDefaultInstance(),
            Vehicle.VehicleData.newBuilder()
                .setClimateState(Vehicle.ClimateState.newBuilder().setInsideTempCelsius(22f)).build(),
            driveFrame(),
            driveFrame(power = 60),
        )) {
            val next = applyStatusDrive(previous, frame, now + 61_000L).copy(mcuUpdatedAtMs = now + 61_000L)
            assertEquals(previous.speedKmh, next.speedKmh)
            assertEquals(previous.shiftState, next.shiftState)
            assertEquals(previous.driveSpeedAtMs, next.driveSpeedAtMs)
            assertEquals(previous.driveShiftAtMs, next.driveShiftAtMs)
            assertEquals(previous.batteryLevel, next.batteryLevel)
            assertEquals(previous.insideTemp, next.insideTemp)
            assertFalse(next.recentlyMoving(now + 61_000L))
            assertEquals(now + 90_000L, nextDrivingUntil(now + 90_000L, parseDriveSample(frame), now + 61_000L))
        }
    }

    @Test
    fun partialFrameDoesNotFreshenOldParkShift() {
        val previous = applyStatusDrive(StatusSnapshot(), driveFrame(0f, "P"), now)
        val next = applyStatusDrive(previous, driveFrame(0f), now + 6_000L)
        assertEquals(now + 6_000L, next.driveSpeedAtMs)
        assertEquals(now, next.driveShiftAtMs)
        assertEquals("P", next.shiftState)
        assertFalse(statusCategoryAllowed(Payloads.Category.CLIMATE, false, 0L, next, now + 6_000L))

        val speedMissing = applyStatusDrive(StatusSnapshot(), driveFrame(shift = "P"), now)
        assertNull(speedMissing.speedKmh)
        assertFalse(statusCategoryAllowed(Payloads.Category.CHARGE, false, 0L, speedMissing, now))
    }

    @Test
    fun dashboardLatchChangedDuringRequestStopsNextClassAndTrailingBodyRead() = runTest {
        val parked = applyStatusDrive(StatusSnapshot(), driveFrame(0f, "P"), now)
        assertTrue(statusCategoryAllowed(Payloads.Category.CHARGE, false, 0L, parked, now))
        // Simulate one bounded request suspended while the dashboard receives D at zero speed.
        delay(2_500L)
        val at = now + testScheduler.currentTime
        val until = nextDrivingUntil(0L, parseDriveSample(driveFrame(0f, "D")), at)
        // Deliberately retain Status's old P snapshot and false vehicleMoving.
        assertFalse(statusCategoryAllowed(Payloads.Category.CLIMATE, false, until, parked, at))
        assertFalse(statusCategoryAllowed(Payloads.Category.LOCATION, false, until, parked, at))
        assertTrue(statusReadsBlocked(false, until, parked, at))
    }

    @Test
    fun sentryCarriesItsOwnClockAndSurvivesUnrelatedMerges() {
        val base = StatusSnapshot(sentryOn = true, sentryAtMs = now)
        assertTrue(base.sentryIsFresh(now))
        assertFalse(base.sentryIsFresh(now + 60_001L))
        // 唤醒探针只读 CHARGE：不带哨兵字段，合并不得把旧值续命成"刚读到的"。
        val chargeOnly = StatusSnapshot(batteryLevel = 75)
        val merged = chargeOnly.mergedInto(base)
        assertEquals(true, merged.sentryOn)
        assertEquals(now, merged.sentryAtMs)
        assertFalse(merged.sentryIsFresh(now + 60_001L))
        // 真正读到 CLOSURES 时时钟才前进。
        val refreshed = StatusSnapshot(sentryOn = false, sentryAtMs = now + 90_000L).mergedInto(base)
        assertEquals(false, refreshed.sentryOn)
        assertEquals(now + 90_000L, refreshed.sentryAtMs)
    }

    @Test
    fun wakeProbeOnlyRunsForUserIntentWithASleepingHeadUnit() {
        // 用户主动进页面 + 车机域没有活会话 + 没在行驶 + 链路已连：唯一允许发唤醒的组合。
        assertTrue(wakeProbeAllowed(force = true, mcuSessionReady = false, readsBlocked = false, linkConnected = true))
        // 自动轮询绝不唤醒（"不再反复唤醒车机"）。
        assertFalse(wakeProbeAllowed(force = false, mcuSessionReady = false, readsBlocked = false, linkConnected = true))
        // 会话活着就不必唤醒；行驶中一次唤醒握手的几十秒会把车速道冻住。
        assertFalse(wakeProbeAllowed(force = true, mcuSessionReady = true, readsBlocked = false, linkConnected = true))
        assertFalse(wakeProbeAllowed(force = true, mcuSessionReady = false, readsBlocked = true, linkConnected = true))
        assertFalse(wakeProbeAllowed(force = true, mcuSessionReady = false, readsBlocked = false, linkConnected = false))
    }

    @Test
    fun wakeProbeStepStopsTheInstantTelemetryLaneWantsTheLink() {
        val deadline = now + 30_000L
        // 正常等待：车机还没起来，但没人要链路，继续一小口一小口地探。
        assertTrue(wakeProbeStepAllowed(telemetryLaneActive = false, readsBlocked = false, nowMs = now, deadlineMs = deadline))
        // 车速道排过一次锁 / 他开始开了 / 超过总时限：立刻收手，不再占车机域锁。
        assertFalse(wakeProbeStepAllowed(telemetryLaneActive = true, readsBlocked = false, nowMs = now, deadlineMs = deadline))
        assertFalse(wakeProbeStepAllowed(telemetryLaneActive = false, readsBlocked = true, nowMs = now, deadlineMs = deadline))
        assertFalse(wakeProbeStepAllowed(telemetryLaneActive = false, readsBlocked = false, nowMs = deadline, deadlineMs = deadline))
    }

    @Test
    fun parkingEvidenceCanExpireBetweenCategories() = runTest {
        val parked = applyStatusDrive(StatusSnapshot(), driveFrame(0f, "P"), now)
        assertTrue(statusCategoryAllowed(Payloads.Category.CLOSURES, false, 0L, parked, now))
        delay(2_500L)
        assertTrue(statusCategoryAllowed(Payloads.Category.CLIMATE, false, 0L, parked, now + testScheduler.currentTime))
        delay(2_500L)
        assertFalse(statusCategoryAllowed(Payloads.Category.CHARGE, false, 0L, parked, now + testScheduler.currentTime))
    }
}
