package com.teslalink.app.debug

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Global debug log: mirrors to Logcat (tag prefix "TeslaLink") and keeps a
 * ring buffer for the in-app debug page.
 */
object DebugLog {
    private const val TAG = "TeslaLink"
    private const val MAX_LINES = 600

    data class Entry(val timeMs: Long, val tag: String, val message: String)

    private val _lines = MutableStateFlow<List<Entry>>(emptyList())
    val lines: StateFlow<List<Entry>> get() = _lines

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

    fun log(tag: String, message: String) {
        val entry = Entry(System.currentTimeMillis(), tag, message)
        _lines.update { (it + entry).takeLast(MAX_LINES) }
        Log.d(TAG, "[$tag] $message")
    }

    fun error(tag: String, message: String, t: Throwable? = null) {
        val entry = Entry(System.currentTimeMillis(), tag, "$message${t?.let { " :: ${it.message}" } ?: ""}")
        _lines.update { (it + entry).takeLast(MAX_LINES) }
        Log.e(TAG, "[$tag] $message", t)
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
        _lines.value = emptyList()
    }
}
