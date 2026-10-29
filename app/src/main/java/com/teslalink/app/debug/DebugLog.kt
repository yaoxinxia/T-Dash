package com.teslalink.app.debug

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Global debug log: mirrors to Logcat (tag prefix "TeslaLink") and keeps a
 * ring buffer for the in-app debug page.
 *
 * 写入路径必须便宜：车速道每秒有多条协议日志，这里曾是"每条拷贝 600 元素环 +
 * 调用点先把 hex 全格式化好"——行驶卡顿的常开贡献者。所以环形缓冲用 ArrayDeque
 * 原位增删，调试页的快照按 200ms 合并发布；逐帧的大消息一律走惰性 v()。
 */
object DebugLog {
    private const val TAG = "TeslaLink"
    private const val MAX_LINES = 600
    private const val PUBLISH_INTERVAL_MS = 200L

    data class Entry(val timeMs: Long, val tag: String, val message: String)

    private val ring = ArrayDeque<Entry>(MAX_LINES)
    private val _lines = MutableStateFlow<List<Entry>>(emptyList())
    val lines: StateFlow<List<Entry>> get() = _lines
    private var lastPublishAt = 0L

    /**
     * Verbose protocol traffic (per-poll SEND/RX/hex) is only recorded when this is on;
     * errors, handshakes and command results are always recorded.
     */
    private val _verbose = MutableStateFlow(false)
    val verboseState: StateFlow<Boolean> get() = _verbose

    @Volatile var verbose: Boolean = false
        private set

    fun setVerbose(on: Boolean) {
        verbose = on
        _verbose.value = on
        log("ui", "详细协议日志 = $on")
    }

    /** Verbose-level log: dropped unless verbose mode is enabled. */
    fun v(tag: String, message: String) {
        if (verbose) log(tag, message)
    }

    /** 惰性版 v()：消息（含 hex 格式化）只有在详细日志开着时才求值。逐帧调用点必须用这个。 */
    fun v(tag: String, message: () -> String) {
        if (verbose) log(tag, message())
    }

    fun log(tag: String, message: String) {
        record(tag, message)
        Log.d(TAG, "[$tag] $message")
    }

    fun error(tag: String, message: String, t: Throwable? = null) {
        record(tag, "$message${t?.let { " :: ${it.message}" } ?: ""}")
        Log.e(TAG, "[$tag] $message", t)
    }

    private fun record(tag: String, message: String) {
        val now = System.currentTimeMillis()
        val publish: Boolean
        synchronized(ring) {
            ring.addLast(Entry(now, tag, message))
            while (ring.size > MAX_LINES) ring.removeFirst()
            publish = now - lastPublishAt >= PUBLISH_INTERVAL_MS
            if (publish) lastPublishAt = now
        }
        if (publish) _lines.value = synchronized(ring) { ring.toList() }
    }

    /** Full packet hex for protocol-layer messages. */
    fun hex(bytes: ByteArray, max: Int = 9999): String {
        val shown = bytes.take(max)
        return shown.joinToString("") { "%02x".format(it) } +
            if (bytes.size > max) "…(+${bytes.size - max})" else ""
    }

    /** Short key fingerprint: lets a log show whether the local key changed between sessions. */
    fun fp(bytes: ByteArray): String =
        bytes.takeLast(4).joinToString("") { "%02x".format(it) }

    fun clear() {
        synchronized(ring) {
            ring.clear()
            lastPublishAt = 0L
        }
        _lines.value = emptyList()
    }
}
