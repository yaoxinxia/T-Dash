package com.teslalink.app.protocol

import com.tesla.generated.universalmessage.UniversalMessage
import com.teslalink.app.vehicle.withReadDeadline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReadDeadlineTest {
    @Test fun deadlineIncludesQueueHandshakeAndResponse() = runTest {
        val stages = mutableListOf<String>()
        val started = testScheduler.currentTime
        val result = runCatching {
            withReadDeadline(2_500) {
                stages += "queue"
                delay(800)
                stages += "handshake"
                delay(900)
                stages += "send"
                delay(400)
                stages += "response"
                delay(800)
                "late"
            }
        }
        assertTrue(result.exceptionOrNull() is TeslaError.Timeout)
        assertEquals(2_500L, testScheduler.currentTime - started)
        assertEquals(listOf("queue", "handshake", "send", "response"), stages)
    }

    @Test fun timedOutQueuedRequestNeverRunsLater() = runTest {
        val lock = Mutex(locked = true)
        var sent = false
        val request = async {
            runCatching { withReadDeadline(2_500) { lock.withLock { sent = true } } }
        }
        advanceTimeBy(2_501)
        runCurrent()
        assertTrue(request.await().exceptionOrNull() is TeslaError.Timeout)
        lock.unlock()
        runCurrent()
        assertFalse(sent)
        assertFalse(lock.isLocked)
    }

    @Test fun timeoutReleasesLockAndAllowsNextRead() = runTest {
        val lock = Mutex()
        val result = runCatching {
            withReadDeadline(2_500) { lock.withLock { delay(8_000); "late" } }
        }
        assertTrue(result.exceptionOrNull() is TeslaError.Timeout)
        assertFalse(lock.isLocked)
        assertEquals("next", withReadDeadline(2_500) { lock.withLock { "next" } })
    }

    @Test fun parentCancellationIsNotReportedAsTimeout() = runTest {
        var cancelled = false
        var timeout = false
        val request = launch {
            try {
                withReadDeadline(2_500) { delay(8_000); Unit }
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            } catch (e: TeslaError.Timeout) {
                timeout = true
            }
        }
        runCurrent()
        request.cancelAndJoin()
        assertTrue(cancelled)
        assertFalse(timeout)
    }

    @Test fun dispatcherStopReleasesResponseWaiterImmediately() = runTest {
        val transport = object : Transport {
            override val incoming = MutableSharedFlow<ByteArray>()
            override suspend fun send(buffer: ByteArray) = Unit
        }
        val dispatcher = Dispatcher("TESTVIN00000000000", null, null, transport)
        val handler = dispatcher.send(
            UniversalMessage.RoutableMessage.newBuilder().setToDestination(
                UniversalMessage.Destination.newBuilder()
                    .setDomain(UniversalMessage.Domain.DOMAIN_VEHICLE_SECURITY)
            ),
            Dispatcher.Auth.NONE,
        )
        val reply = async { runCatching { handler.channel.receive() } }
        runCurrent()
        dispatcher.stop()
        runCurrent()
        assertTrue(reply.isCompleted)
        assertTrue(reply.await().exceptionOrNull() is TeslaError.NotConnected)
    }
}
