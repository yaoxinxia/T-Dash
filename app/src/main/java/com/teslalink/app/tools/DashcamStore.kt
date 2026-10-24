package com.teslalink.app.tools

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

data class DashcamClip(val camera: String, val startEpochMs: Long, val uri: Uri)

class DashcamEvent(
    val folderName: String,
    val source: String,
    val clips: Map<String, List<DashcamClip>>,
    val city: String? = null,
    val street: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val reason: String? = null,
    val triggerMs: Long? = null,
    val cameraId: Int? = null,
) {
    val startMs: Long get() = clips.values.minOfOrNull { it.first().startEpochMs } ?: 0
    val endMs: Long get() = clips.values.maxOfOrNull { it.last().startEpochMs + SEGMENT_MS } ?: 0
    val clipCount: Int get() = clips.values.sumOf { it.size }

    /** 每个机位的分段数：同一分钟六个相机各一个文件，"66 个文件"对用户毫无意义，"11 段"才是他理解的分段。 */
    val segmentCount: Int get() = clips.values.maxOfOrNull { it.size } ?: 0

    /** reason 里带的加速度（哨兵事件），是"有没有被撞到"的直接线索。 */
    val accelG: Double? get() = ACCEL_RE.find(reason ?: "")?.groupValues?.get(1)?.toDoubleOrNull()

    /** 撞击/异常加速度：界面要标红，事故取证优先看这些。 */
    val isImpact: Boolean get() = accelG != null

    val reasonText: String
        get() = when {
            reason == null -> "滚动录像"
            reason.startsWith("user_interaction") -> "手动保存（记录仪按键）"
            reason.contains("object_detection") -> "哨兵：物体靠近"
            reason.contains("panic_accel") -> "哨兵 panic：加速度 ${accelG?.let { "%.2f".format(it) } ?: "?"}"
            reason.contains("accel") -> "哨兵：加速度 ${accelG?.let { "%.2f".format(it) } ?: "?"}"
            else -> reason
        }

    companion object {
        /** 实测分段约 59.5 s，估算时长用（外部也读：进度条还没拿到真实时长时按它估）。 */
        const val SEGMENT_MS = 59_500L

        private val ACCEL_RE = Regex("accel_([0-9.]+)")
    }

    /**
     * 触发时刻落在某机位序列里的第几段 + 段内偏移。
     * 哨兵/手动事件目录里装的是触发前约 10 分钟的预录，所以触发点几乎总在最后几段——
     * 界面要能一键跳过去，否则 66 段里手动翻很痛苦。
     */
    fun triggerIn(cam: String): Pair<Int, Long>? {
        val t = triggerMs ?: return null
        val list = clips[cam] ?: return null
        if (list.isEmpty()) return null
        for (i in list.indices) {
            val s = list[i].startEpochMs
            if (t in s until s + SEGMENT_MS) return i to (t - s)
        }
        return if (t < list.first().startEpochMs) 0 to 0L
        else (list.size - 1) to (SEGMENT_MS - 1_000)
    }
}

/**
 * 行车记录仪 U 盘扫描（SAF，无需存储权限）。
 *
 * 实测（2026-10 中国版 HW4，E:\ 一整盘 1086 个滚动分段 + 23 哨兵 + 19 手动）：
 *  - `RecentClips/` 是**平铺**的 `2026-10-16_17-19-34-front.mp4`（时间戳在前、机位在后，无事件子目录）；
 *  - `SavedClips/`、`SentryClips/` 每事件一个目录，段与段同名跨 6 机位；目录里除了 mp4 还有
 *    `event.json`（timestamp/city/street/est_lat/est_lon/reason/camera）和 `thumb.png`（车辆自截 128x83）；
 *  - 事件目录装的是**触发前约 10 分钟的预录**：最早的分段比 `event.json` 的 timestamp 早 560~660 秒，
 *    所以"撞击那一刻"通常在序列末尾，用 [DashcamEvent.triggerIn] 定位；
 *  - 机位共 6 个：front / back / left_repeater / right_repeater / left_pillar / right_pillar；
 *  - 老固件的 `front-<时间戳>.mp4` 命名同样能解析。
 */
object DashcamStore {

    /** 机位显示顺序与名称。 */
    val CAMERA_ORDER = listOf(
        "front", "back", "left_repeater", "right_repeater", "left_pillar", "right_pillar",
    )

    private val TS_RE = Regex("""(\d{4})-(\d{2})-(\d{2})_(\d{2})-(\d{2})-(\d{2})""")

    /**
     * 列目录。拔盘或 SAF 授权过期时 listFiles 会抛 SecurityException/IllegalStateException，
     * 单个目录读不动不该带走整次扫描，更不该让 app 崩——但要留下"读不动"这个事实，
     * 否则扫描"成功"返回空列表，界面只能误导性地报"没找到分段"。
     */
    private fun kidsOf(dir: DocumentFile, where: String, unreadable: MutableList<String>): List<DocumentFile> =
        runCatching { dir.listFiles().toList() }
            .onFailure {
                unreadable += "$where：${it.javaClass.simpleName}"
                com.teslalink.app.debug.DebugLog.error(
                    "dashcam", "列目录失败（$where）：${it.javaClass.name}: ${it.message}", it,
                )
            }
            .getOrDefault(emptyList())

    fun camLabel(cam: String): String = when (cam) {
        "front" -> "前视"
        "back" -> "后视"
        "left_repeater" -> "左后侧"
        "right_repeater" -> "右后侧"
        "left_pillar" -> "左B柱"
        "right_pillar" -> "右B柱"
        else -> cam
    }

    /** 扫描结果。**unreadable=true 表示有目录根本读不动**（盘被拔/授权失效/IO 错），
     *  和"目录读得动但里面没有 TeslaCam 分段"是两回事，界面必须分开说。 */
    data class ScanResult(val events: List<DashcamEvent>, val unreadable: List<String>)

    fun scan(context: Context, treeUri: Uri): ScanResult {
        val unreadable = mutableListOf<String>()
        val picked = DocumentFile.fromTreeUri(context, treeUri)
        if (picked == null) {
            return ScanResult(emptyList(), listOf("所选目录打不开（授权可能已失效）"))
        }
        val started = System.currentTimeMillis()
        val root = if (picked.name.equals("TeslaCam", true)) picked else {
            // 一次列根目录，别按名字逐个 findFile：SAF 上每次 findFile 都是一趟跨进程查询。
            kidsOf(picked, "所选目录", unreadable).firstOrNull { it.name.equals("TeslaCam", true) && it.isDirectory } ?: picked
        }
        val events = mutableListOf<DashcamEvent>()
        val rootKids = kidsOf(root, "root", unreadable).associateBy { it.name?.lowercase().orEmpty() }
        if (rootKids.isEmpty() && unreadable.isNotEmpty()) {
            return ScanResult(emptyList(), unreadable)
        }

        for (src in listOf("SentryClips", "SavedClips")) {
            val dir = rootKids[src.lowercase()]?.takeIf { it.isDirectory } ?: continue
            kidsOf(dir, src, unreadable).filter { it.isDirectory }.forEach { ev ->
                parseEventDir(context, ev, src.removeSuffix("Clips"), unreadable)?.let { events += it }
            }
        }
        // RecentClips：平铺文件按时间戳分组，再按 5 分钟空档切成一段段行程。
        rootKids["recentclips"]?.takeIf { it.isDirectory }?.let { recent ->
            val clips = kidsOf(recent, "RecentClips", unreadable).mapNotNull { parseClip(it) }
            events += groupRolling(clips)
        }
        // 兜底：直接选了某个事件目录，或 mp4 就在根目录。
        if (events.isEmpty()) {
            parseEventDir(context, root, "ROOT", unreadable)?.let { events += it }
        }
        com.teslalink.app.debug.DebugLog.log(
            "dashcam",
            "扫描完成：${events.size} 组，用时 ${System.currentTimeMillis() - started} ms" +
                (if (unreadable.isNotEmpty()) "，${unreadable.size} 个目录读不动" else ""),
        )
        return ScanResult(events.sortedByDescending { it.startMs }, unreadable)
    }

    /**
     * 逐段探**真实时长**（只探主机位那几个文件，11 段约 1 秒，IO 线程调用）。
     * **每段独立兜底**：`openAssetFileDescriptor` 本身也会抛（拔盘 FileNotFoundException、
     * 授权回收 SecurityException）——单段失败回落墙钟估算值，绝不能让探测把 app 带走
     * （调用方在 LaunchedEffect 里，未捕获异常就是闪退）。
     *
     * 为什么不能只靠播放器的时间轴：media3 对还没准备到的 playlist 项回报占位时长
     * （TIME_UNSET），"等所有窗口都拿到真值再替换"基本不会发生——总长就一直停在按墙钟差
     * 估的 60s/段，比真值多攒出好几秒。表现正是他连报两次的：跳到触发时刻后画面已到真正
     * 末尾、状态写"已播完"，进度条却还差一截。
     */
    fun segmentDurations(context: Context, clips: List<DashcamClip>): List<Long> = clips.map { clip ->
        try {
            val afd = context.contentResolver.openAssetFileDescriptor(clip.uri, "r")
            if (afd == null) DashcamEvent.SEGMENT_MS
            else {
                try {
                    val r = android.media.MediaMetadataRetriever()
                    try {
                        if (afd.getDeclaredLength() < 0) r.setDataSource(afd.fileDescriptor)
                        else r.setDataSource(afd.fileDescriptor, afd.startOffset, afd.getDeclaredLength())
                        (r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                            ?.toLongOrNull() ?: DashcamEvent.SEGMENT_MS).coerceAtLeast(1_000L)
                    } finally {
                        runCatching { r.release() }
                    }
                } catch (_: Exception) {
                    DashcamEvent.SEGMENT_MS
                } finally {
                    runCatching { afd.close() }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            DashcamEvent.SEGMENT_MS
        }
    }

    /** 文件名 → (机位, 起始时刻)；两种命名顺序都吃。 */
    private fun parseClip(f: DocumentFile): DashcamClip? {
        val name = f.name ?: return null
        if (!name.endsWith(".mp4", ignoreCase = true)) return null
        val stem = name.substringBeforeLast('.')
        val m = TS_RE.find(stem) ?: return null
        val cam = stem.removeRange(m.range).trim('-', '_', ' ').lowercase()
        if (cam.isEmpty() || !cam.all { it.isLetter() || it == '_' }) return null
        val g = m.groupValues
        val cal = Calendar.getInstance().apply {
            set(
                g[1].toInt(), g[2].toInt() - 1, g[3].toInt(),
                g[4].toInt(), g[5].toInt(), g[6].toInt(),
            )
            set(Calendar.MILLISECOND, 0)
        }
        return DashcamClip(cam, cal.timeInMillis, f.uri)
    }

    private fun parseEventDir(
        context: Context,
        dir: DocumentFile,
        source: String,
        unreadable: MutableList<String>,
    ): DashcamEvent? {
        // 一个事件目录只列一次。原来 mp4 / event.json / thumb.png 各查一遍，
        // 等于每个目录三趟跨进程查询——几十个事件目录就是几秒到十几秒。
        val kids = kidsOf(dir, "事件目录 ${dir.name}", unreadable)
        val clips = kids.mapNotNull { parseClip(it) }
            .groupBy { it.camera }
            .mapValues { (_, v) -> v.sortedBy { c -> c.startEpochMs } }
        if (clips.isEmpty()) return null
        val meta = readEventJson(context, kids.firstOrNull { it.name == "event.json" })
        return DashcamEvent(
            folderName = dir.name ?: "",
            source = source,
            clips = orderCameras(clips),
            city = meta?.optString("city")?.ifBlank { null },
            street = meta?.optString("street")?.ifBlank { null },
            lat = meta?.optString("est_lat")?.toDoubleOrNull(),
            lon = meta?.optString("est_lon")?.toDoubleOrNull(),
            reason = meta?.optString("reason")?.ifBlank { null },
            triggerMs = meta?.optString("timestamp")?.let { parseIso(it) },
            cameraId = meta?.optString("camera")?.toIntOrNull(),
        )
    }

    /** `2026-10-16T18:05:29` → 本地时区毫秒；车机记的是"触发那一刻"，比目录名更早或更晚几秒。 */
    private fun parseIso(s: String): Long? = try {
        TS_FORMAT.parse(s)?.time
    } catch (_: Exception) {
        null
    }

    private val TS_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

    /** event.json：车自己记录的城市、粗略经纬度与触发原因（含加速度数值）。 */
    private fun readEventJson(context: Context, f: DocumentFile?): JSONObject? = try {
        f?.uri?.let { uri ->
            context.contentResolver.openInputStream(uri)?.use { input ->
                JSONObject(input.readBytes().toString(Charsets.UTF_8))
            }
        }
    } catch (_: Exception) {
        null
    }

    private fun orderCameras(map: Map<String, List<DashcamClip>>): Map<String, List<DashcamClip>> =
        map.toSortedMap(compareBy { CAMERA_ORDER.indexOf(it).let { i -> if (i < 0) 99 else i } })

    /** 滚动录像按时间连续性切段（空档 > 5 分钟算新的一段行程）。 */
    private fun groupRolling(clips: List<DashcamClip>): List<DashcamEvent> {
        if (clips.isEmpty()) return emptyList()
        val stamps = clips.map { it.startEpochMs }.distinct().sorted()
        val groups = mutableListOf<MutableList<Long>>()
        var current = mutableListOf(stamps.first())
        for (i in 1 until stamps.size) {
            if (stamps[i] - stamps[i - 1] > 5 * 60_000) {
                groups += current
                current = mutableListOf()
            }
            current += stamps[i]
        }
        groups += current

        return groups.map { group ->
            val set = group.toHashSet()
            val byCam = clips.filter { it.startEpochMs in set }.groupBy { it.camera }
                .mapValues { (_, v) -> v.sortedBy { c -> c.startEpochMs } }
            DashcamEvent(
                folderName = "滚动录像",
                source = "Recent",
                clips = orderCameras(byCam),
            )
        }
    }
}
