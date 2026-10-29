package com.teslalink.app.ble

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay

/** Per-connection bookkeeping. All access is serialized by BleTransport's GATT lock. */
internal class GattSession<H : Any> {
    enum class Step(val label: String) {
        CONNECT("GATT 连接"), MTU("MTU 协商"), SERVICES("服务发现"), CCCD("订阅车辆通知"),
    }

    data class Reply(val status: Int, val mtu: Int = 23)

    var handle: H? = null
        private set
    var ready = false
        private set
    private var closed = false
    private var failure: Throwable? = null
    private var nextStep = 0
    private var step: Step? = null
    private var stepReply: Reply? = null
    private var stepWaiter: CompletableDeferred<Reply>? = null
    private var writeReply: Int? = null
    private var writeWaiter: CompletableDeferred<Int>? = null

    fun attach(handle: H) {
        checkOpen()
        check(this.handle == null)
        this.handle = handle
    }

    fun owns(handle: H): Boolean = !closed && this.handle === handle

    fun checkOpen() {
        failure?.let { throw it }
        check(!closed) { "GATT 已释放" }
    }

    fun begin(step: Step): CompletableDeferred<Reply> {
        checkOpen()
        check(this.step == null && step.ordinal == nextStep) { "GATT 建链步骤顺序错误: $step" }
        this.step = step
        return CompletableDeferred<Reply>().also { stepWaiter = it }
    }

    /** Callbacks only report once; they never advance the setup sequence. */
    fun report(step: Step, reply: Reply): Boolean {
        if (closed || this.step != step || stepReply != null) return false
        stepReply = reply
        return stepWaiter?.complete(reply) == true
    }

    fun finish(step: Step) {
        checkOpen()
        check(this.step == step)
        val reply = checkNotNull(stepReply) { "GATT 步骤还没有回执: $step" }
        // MTU failure is the only callback failure with a safe default (ATT MTU 23).
        check(reply.status == 0 || step == Step.MTU) { "${step.label}失败 status=${reply.status}" }
        this.step = null
        stepWaiter = null
        stepReply = null
        nextStep++
        ready = nextStep == Step.entries.size
    }

    fun beginWrite(): CompletableDeferred<Int> {
        checkOpen()
        check(ready && handle != null) { "BLE 未连接或通知订阅未完成" }
        check(writeWaiter == null) { "GATT 写入尚未结束" }
        return CompletableDeferred<Int>().also { writeWaiter = it }
    }

    fun reportWrite(status: Int): Boolean {
        if (closed || writeWaiter == null || writeReply != null) return false
        writeReply = status
        return writeWaiter?.complete(status) == true
    }

    fun finishWrite(waiter: CompletableDeferred<Int>) {
        checkOpen()
        check(writeWaiter === waiter)
        val status = checkNotNull(writeReply) { "GATT 写入还没有回执" }
        check(status == 0) { "BLE 写入失败 status=$status" }
        writeWaiter = null
        writeReply = null
    }

    /** Detach first, then wake waiters. The caller disconnects/closes the returned handle. */
    fun close(cause: Throwable): H? {
        if (closed) return null
        val detached = handle
        handle = null
        closed = true
        ready = false
        failure = cause
        val setup = stepWaiter
        val write = writeWaiter
        stepWaiter = null
        writeWaiter = null
        stepReply = null
        writeReply = null
        setup?.completeExceptionally(cause)
        write?.completeExceptionally(cause)
        return detached
    }
}

/** Cleanup is synchronous, including when the calling coroutine is already cancelled. */
internal suspend fun <T> withGattCleanup(release: (Throwable) -> Unit, action: suspend () -> T): T {
    try {
        return action()
    } catch (failure: Throwable) {
        release(failure)
        throw failure
    }
}

/** Retry only a synchronous BUSY rejection: no operation/callback is in flight yet. */
internal suspend fun retryGattBusy(
    busyCode: Int,
    maxAttempts: Int = 3,
    retryDelayMs: Long = 100,
    write: () -> Int,
): Int {
    require(maxAttempts > 0)
    repeat(maxAttempts - 1) {
        val status = write()
        if (status != busyCode) return status
        delay(retryDelayMs)
    }
    return write()
}
