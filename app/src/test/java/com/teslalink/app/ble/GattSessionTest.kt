package com.teslalink.app.ble

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests the bookkeeping/retry/cleanup used by BleTransport, without Android GATT stubs. */
class GattSessionTest {
    @Test fun setupCannotSkipAStageOrBecomeReadyWithoutCccdReceipt() = runBlocking {
        val session = newSession()
        for (step in GattSession.Step.entries) {
            assertFalse(session.ready)
            expectFailure<IllegalStateException> { session.beginWrite() }
            val wrongStep = GattSession.Step.entries[(step.ordinal + 1) % GattSession.Step.entries.size]
            expectFailure<IllegalStateException> { session.begin(wrongStep) }
            val waiter = session.begin(step)
            expectFailure<IllegalStateException> { session.begin(step) }
            expectFailure<IllegalStateException> { session.finish(step) }
            assertFalse(waiter.isCompleted)
            assertFalse(session.report(wrongStep, GattSession.Reply(0)))
            assertFalse(waiter.isCompleted)
            assertTrue(session.report(step, GattSession.Reply(0)))
            assertFalse(session.ready) // A callback reports; only the owner advances.
            assertEquals(0, waiter.await().status)
            assertFalse(session.report(step, GattSession.Reply(133)))
            session.finish(step)
        }
        assertTrue(session.ready)
        assertFalse(session.report(GattSession.Step.CCCD, GattSession.Reply(133)))
    }

    @Test fun duplicateMtuAndServiceCallbacksCannotCompleteCccdOrRestartDiscovery() = runBlocking {
        val session = before(GattSession.Step.CCCD)
        val cccd = session.begin(GattSession.Step.CCCD)
        repeat(3) {
            assertFalse(session.report(GattSession.Step.CONNECT, GattSession.Reply(0)))
            assertFalse(session.report(GattSession.Step.MTU, GattSession.Reply(0, 517)))
            assertFalse(session.report(GattSession.Step.SERVICES, GattSession.Reply(0)))
        }
        assertFalse(cccd.isCompleted)
        assertFalse(session.ready)
        expectFailure<IllegalStateException> { session.begin(GattSession.Step.SERVICES) }
        session.report(GattSession.Step.CCCD, GattSession.Reply(0))
        session.finish(GattSession.Step.CCCD)
        assertTrue(session.ready)
    }

    @Test fun mtuMayUseDefaultButCannotChangeAfterTheStepFinishes() = runBlocking {
        for (status in listOf(0, 133)) {
            val session = before(GattSession.Step.MTU)
            val waiter = session.begin(GattSession.Step.MTU)
            // Same default reply as requestMtu(false), or an unsuccessful MTU callback.
            session.report(GattSession.Step.MTU, GattSession.Reply(status))
            assertEquals(23, waiter.await().mtu)
            session.finish(GattSession.Step.MTU)
            val services = session.begin(GattSession.Step.SERVICES)
            assertFalse(session.report(GattSession.Step.MTU, GattSession.Reply(0, 517)))
            assertFalse(services.isCompleted)
            assertFalse(session.ready)
        }
    }

    @Test fun callbackFailureAtEveryRequiredStageReleasesTheOwnedHandle() = runBlocking {
        for (step in listOf(GattSession.Step.CONNECT, GattSession.Step.SERVICES, GattSession.Step.CCCD)) {
            val session = before(step)
            val handle = session.handle
            var released: Any? = null
            val failure = expectFailure<IllegalStateException> {
                withGattCleanup(release = { released = session.close(it) }) {
                    val waiter = session.begin(step)
                    session.report(step, GattSession.Reply(133))
                    waiter.await()
                    session.finish(step)
                }
            }
            assertTrue(failure.message.orEmpty().contains("status=133"))
            assertSame(handle, released)
            assertNull(session.handle)
            assertFalse(session.ready)
            assertFalse(session.report(step, GattSession.Reply(0)))
        }
    }

    @Test fun synchronousEnqueueFailureKeepsItsReasonAndClosesTheSession() = runBlocking {
        val session = before(GattSession.Step.CCCD)
        val waiter = session.begin(GattSession.Step.CCCD)
        val reason = IllegalStateException("writeDescriptor statusCode=201")
        val failure = expectFailure<IllegalStateException> {
            withGattCleanup(release = { session.close(it) }) { throw reason }
        }
        assertSame(reason, failure)
        // Deferred.await() rethrows a stack-trace copy of a non-cancellation cause, so
        // compare the reason it carries rather than its identity.
        assertEquals(reason.message, expectFailure<IllegalStateException> { waiter.await() }.message)
        assertNull(session.handle)
        assertFalse(session.ready)
    }

    @Test fun disconnectDetachesBeforeWakingWaitersAndIsIdempotent() = runBlocking {
        for (step in GattSession.Step.entries) {
            val session = before(step)
            val handle = session.handle
            val waiter = session.begin(step)
            val cause = IllegalStateException("disconnected")
            var resumed = false
            val waiting = launch(start = CoroutineStart.UNDISPATCHED) {
                assertEquals(cause.message, expectFailure<IllegalStateException> { waiter.await() }.message)
                assertNull(session.handle)
                assertFalse(session.ready)
                resumed = true
            }
            assertSame(handle, session.close(cause))
            assertNull(session.close(cause))
            waiting.join()
            assertTrue(resumed)
            assertSame(cause, expectFailure<IllegalStateException> { session.attach(Any()) })
        }
    }

    @Test fun cancellationAtEverySetupWaitRunsProductionCleanup() = runBlocking {
        for (step in GattSession.Step.entries) {
            val session = before(step)
            var cleanups = 0
            var cause: Throwable? = null
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                withGattCleanup(release = {
                    cleanups++
                    cause = it
                    session.close(it)
                }) {
                    session.begin(step).await()
                    session.finish(step)
                }
            }
            job.cancelAndJoin()
            assertEquals(1, cleanups)
            assertTrue(cause is CancellationException)
            assertNull(session.handle)
            assertFalse(session.ready)
            assertFalse(session.report(step, GattSession.Reply(0)))
        }
    }

    @Test fun cancellationAfterCallbackBeforeResumeCannotPublishReady() = runBlocking {
        val session = before(GattSession.Step.CCCD)
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            withGattCleanup(release = { session.close(it) }) {
                session.begin(GattSession.Step.CCCD).await()
                session.finish(GattSession.Step.CCCD)
            }
        }
        session.report(GattSession.Step.CCCD, GattSession.Reply(0))
        job.cancelAndJoin()
        assertNull(session.handle)
        assertFalse(session.ready)
    }

    @Test fun oldSessionCallbacksAndCleanupCannotAffectNewSession() = runBlocking {
        val old = readySession()
        val oldHandle = checkNotNull(old.handle)
        old.close(IllegalStateException("replaced"))
        val current = before(GattSession.Step.CCCD)
        val currentHandle = checkNotNull(current.handle)
        val cccd = current.begin(GattSession.Step.CCCD)
        assertFalse(old.owns(oldHandle))
        assertFalse(current.owns(oldHandle))
        assertTrue(current.owns(currentHandle))
        assertFalse(old.report(GattSession.Step.CCCD, GattSession.Reply(0)))
        assertFalse(old.report(GattSession.Step.SERVICES, GattSession.Reply(133)))
        assertNull(old.close(CancellationException("late cleanup")))
        assertFalse(cccd.isCompleted)
        assertSame(currentHandle, current.handle)
        assertTrue(current.report(GattSession.Step.CCCD, GattSession.Reply(0)))
        current.finish(GattSession.Step.CCCD)
        assertTrue(current.ready)
    }

    @Test fun writeReceiptsAreSingleUseAndCannotOverlap() = runBlocking {
        val session = readySession()
        val waiter = session.beginWrite()
        expectFailure<IllegalStateException> { session.beginWrite() }
        assertTrue(session.reportWrite(0))
        assertFalse(session.reportWrite(133))
        assertEquals(0, waiter.await())
        session.finishWrite(waiter)
        assertFalse(session.reportWrite(0))
        val next = session.beginWrite()
        assertFalse(next.isCompleted)
        session.reportWrite(0)
        session.finishWrite(next)
    }

    @Test fun writeFailureReleasesGattRatherThanContinuingAPartialFrame() = runBlocking {
        val session = readySession()
        val failure = expectFailure<IllegalStateException> {
            withGattCleanup(release = { session.close(it) }) {
                val waiter = session.beginWrite()
                session.reportWrite(133)
                waiter.await()
                session.finishWrite(waiter)
            }
        }
        assertTrue(failure.message.orEmpty().contains("status=133"))
        assertNull(session.handle)
        expectFailure<IllegalStateException> { session.beginWrite() }
        Unit
    }

    @Test fun writeTimeoutRetiresGattAndLateReceiptCannotSatisfyNextConnection() = runBlocking {
        val old = readySession()
        expectFailure<TimeoutCancellationException> {
            withGattCleanup(release = { old.close(it) }) {
                withTimeout(10) { old.beginWrite().await() }
            }
        }
        assertNull(old.handle)
        assertFalse(old.ready)
        val current = readySession()
        val nextWrite = current.beginWrite()
        assertFalse(old.reportWrite(0))
        assertFalse(nextWrite.isCompleted)
        assertTrue(current.reportWrite(0))
        current.finishWrite(nextWrite)
    }

    @Test fun cancellingSecondChunkRetiresGattAndWakesTheWriteWaiter() = runBlocking {
        val session = readySession()
        val first = session.beginWrite()
        session.reportWrite(0)
        session.finishWrite(first)
        val second = session.beginWrite()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            withGattCleanup(release = { session.close(it) }) { second.await() }
        }
        job.cancelAndJoin()
        assertNull(session.handle)
        assertTrue(second.isCompleted)
        expectFailure<CancellationException> { second.await() }
        expectFailure<CancellationException> { session.beginWrite() }
        assertFalse(session.reportWrite(0))
    }

    @Test fun disconnectWakesPendingWriteWithoutWaitingForItsTimeout() = runBlocking {
        val session = readySession()
        val write = session.beginWrite()
        val cause = IllegalStateException("disconnected")
        session.close(cause)
        assertTrue(write.isCompleted)
        assertEquals(cause.message, expectFailure<IllegalStateException> { write.await() }.message)
        assertFalse(session.reportWrite(0))
    }

    @Test fun busyRetriesOnSameGattButSuccessStillNeedsCccdCallback() = runBlocking {
        val session = before(GattSession.Step.CCCD)
        val handle = session.handle
        val waiter = session.begin(GattSession.Step.CCCD)
        var calls = 0
        val status = retryGattBusy(busyCode = BUSY, retryDelayMs = 0) {
            assertSame(handle, session.handle)
            calls++
            if (calls < 3) BUSY else 0
        }
        assertEquals(0, status)
        assertEquals(3, calls)
        assertFalse(session.ready)
        assertFalse(waiter.isCompleted)
        session.report(GattSession.Step.CCCD, GattSession.Reply(0))
        session.finish(GattSession.Step.CCCD)
        assertTrue(session.ready)
    }

    @Test fun busyRetriesAreBoundedAndPreserveTheFinalStatusCode() = runBlocking {
        var calls = 0
        val status = retryGattBusy(busyCode = BUSY, retryDelayMs = 0) { calls++; BUSY }
        assertEquals(3, calls)
        assertEquals(BUSY, status)
    }

    @Test fun noOtherStatusIsRetriedOrSilentlyConvertedToSuccess() = runBlocking {
        for (code in listOf(0, 1, 6, 9, 200, Int.MAX_VALUE)) {
            var calls = 0
            val status = retryGattBusy(busyCode = BUSY, retryDelayMs = 0) { calls++; code }
            assertEquals(code, status)
            assertEquals(1, calls)
        }
        var calls = 0
        val status = retryGattBusy(busyCode = BUSY, retryDelayMs = 0) { if (++calls == 1) BUSY else 200 }
        assertEquals(200, status)
        assertEquals(2, calls)
    }

    @Test fun cancellingBusyBackoffDoesNotRetryAndRunsCleanup() = runBlocking {
        val session = before(GattSession.Step.CCCD)
        val waiter = session.begin(GattSession.Step.CCCD)
        var calls = 0
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            withGattCleanup(release = { session.close(it) }) {
                retryGattBusy(busyCode = BUSY, retryDelayMs = 60_000) { calls++; BUSY }
            }
        }
        job.cancelAndJoin()
        assertEquals(1, calls)
        assertNull(session.handle)
        assertFalse(session.ready)
        expectFailure<CancellationException> { waiter.await() }
        Unit
    }

    @Test fun successfulOperationDoesNotReleaseTheConnection() = runBlocking {
        val session = readySession()
        var released = false
        assertEquals(42, withGattCleanup(release = { released = true; session.close(it) }) { 42 })
        assertFalse(released)
        assertTrue(session.ready)
        assertNotNull(session.handle)
    }

    private fun newSession() = GattSession<Any>().also { it.attach(Any()) }

    private fun before(target: GattSession.Step): GattSession<Any> = newSession().also { session ->
        GattSession.Step.entries.take(target.ordinal).forEach { step ->
            session.begin(step)
            session.report(step, GattSession.Reply(0))
            session.finish(step)
        }
    }

    private fun readySession(): GattSession<Any> = before(GattSession.Step.CCCD).also {
        it.begin(GattSession.Step.CCCD)
        it.report(GattSession.Step.CCCD, GattSession.Reply(0))
        it.finish(GattSession.Step.CCCD)
    }

    private suspend inline fun <reified T : Throwable> expectFailure(action: suspend () -> Unit): T {
        try {
            action()
        } catch (failure: Throwable) {
            if (failure is T) return failure
            throw AssertionError("Expected ${T::class.java.name}, got $failure", failure)
        }
        throw AssertionError("Expected ${T::class.java.name}")
    }

    private companion object {
        const val BUSY = 201 // BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY, no Android dependency.
    }
}
