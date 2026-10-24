package com.teslalink.app.debug

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃现场必须活过进程死亡。内存里的日志环在闪退那一刻全丢，重启后什么都查不到——
 * 他实测的"P 档连接时闪退、D 回 P 更严重"就是这个局面：现象清楚，栈一行都没有。
 *
 * 所以：未捕获异常发生时把栈 + 崩溃前最后的日志写进私有文件；下次启动再读出来顶在调试页，
 * 点「知道了」删掉，不会每次开 app 重复报同一件事。
 * **保留最近 [MAX_FILES] 份**：连环崩时（用户还没进调试页又崩了第二次）旧现场不再被覆盖——
 * 单文件时代第二次崩溃会把第一次的栈吃掉，而那往往才是要抓的那只。
 */
object CrashLog {
    private const val PREFIX = "crash_"
    private const val SUFFIX = ".txt"
    private const val MAX_KEEP = 60_000
    private const val MAX_FILES = 3

    /** 本次启动恢复到的上次崩溃现场（null = 上次正常退出）。 */
    val pending = MutableStateFlow<String?>(null)

    @Volatile private var pendingFile: File? = null

    fun install(context: Context) {
        val dir = File(context.filesDir, "crash").apply { mkdirs() }
        val files = dir.listFiles { f -> f.name.startsWith(PREFIX) && f.name.endsWith(SUFFIX) }
            ?.sortedByDescending { it.name } ?: emptyList()
        // 最新的一份顶到调试页，其余最多再留 2 份备查（现场原文只有复制入口，不再重复弹）。
        files.drop(MAX_FILES).forEach { runCatching { it.delete() } }
        files.firstOrNull()?.let { newest ->
            val previous: String? = runCatching { newest.readText() }.getOrNull()
            if (!previous.isNullOrBlank()) {
                pending.value = previous
                pendingFile = newest
                DebugLog.error("crash", "上次运行异常退出（现场已恢复，见调试页顶部）")
            } else {
                runCatching { newest.delete() }
            }
        }

        val parent = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                dir.mkdirs()
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                File(dir, "$PREFIX$stamp$SUFFIX").writeText(
                    render(thread.name, error, DebugLog.lines.value.takeLast(40)),
                )
                // 写完新的顺手裁掉超额的旧现场。
                dir.listFiles { f -> f.name.startsWith(PREFIX) && f.name.endsWith(SUFFIX) }
                    ?.sortedByDescending { it.name }
                    ?.drop(MAX_FILES)
                    ?.forEach { runCatching { it.delete() } }
            }
            // 交回系统默认处理：该杀进程还是要杀，别把崩溃变成"卡住不动"。
            parent?.uncaughtException(thread, error)
        }
    }

    /** 调试页「知道了」：清掉内存里的现场并删除对应文件（不再每次开 app 重复报）。 */
    fun dismiss() {
        pending.value = null
        pendingFile?.let { runCatching { it.delete() } }
        pendingFile = null
    }

    private fun render(threadName: String, error: Throwable, tail: List<DebugLog.Entry>): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        return buildString {
            appendLine("=== $stamp 线程 $threadName ===")
            appendChain(error, 3)
            appendLine("--- 崩溃前 ${tail.size} 行日志 ---")
            val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
            tail.forEach { appendLine("${fmt.format(Date(it.timeMs))} [${it.tag}] ${it.message}") }
        }.take(MAX_KEEP)
    }

    /** 异常链 + 每层截断的栈：Compose/BLE 的崩溃常把真正的原因埋在 cause 里。 */
    private fun StringBuilder.appendChain(error: Throwable, depth: Int) {
        var e: Throwable? = error
        var level = 0
        while (e != null && level <= depth) {
            val prefix = if (level == 0) "" else "caused by "
            appendLine("$prefix${e.javaClass.name}: ${e.message}")
            e.stackTrace.take(if (level == 0) 16 else 6).forEach { appendLine("    at $it") }
            e = e.cause?.takeIf { it !== e }
            level++
        }
    }
}
