package com.teslalink.app.vm

import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class TripPoint(val t: Long, val lat: Double, val lng: Double, val heading: Int?, val kmh: Float)

class Trip(
    /** 接续上一段时会被改成更早的起点，所以不是 val。 */
    var startedAt: Long,
    var endedAt: Long = 0,
    val points: MutableList<TripPoint> = mutableListOf(),
    /** 行程内第一次读到的里程计（0.01 英里），之后每帧刷新终点。null = 这台车没回这个字段。 */
    var startOdoHm: Int? = null,
    var endOdoHm: Int? = null,
    /** 250ms 采样维护的峰值；老轨迹没这个键就退回 10s GPS 点里的最大值。 */
    var peakKmh: Float? = null,
    /** 用户点过"接续上一段"：这一段还没收尾时不再重复提供接续（否则会连着更早的旧行程）。 */
    var continued: Boolean = false,
) {
    val maxKmh: Float
        get() = peakKmh ?: points.maxOfOrNull { it.kmh } ?: 0f
    val durationMs: Long
        get() = (if (endedAt > 0) endedAt else points.lastOrNull()?.t ?: startedAt) - startedAt

    /** 里程计差值折成公里；读不到、或者出现倒退（异常数据）时返回 null 交给 GPS 兜底。 */
    val odometerDeltaKm: Double?
        get() {
            val s = startOdoHm ?: return null
            val e = endOdoHm ?: return null
            if (e < s) return null
            return (e - s) * 0.0160934
        }

    /**
     * 本次里程的口径：优先车轮里程计差值（不受 GPS 漂移/隧道影响，且就在已在收的 DRIVE 包里），
     * 没有才回落到 GPS 逐段累加。一格 16m，短途有量化误差，但比 GPS 在楼间的抖动更可信。
     */
    val distanceKm: Double
        get() = odometerDeltaKm ?: distanceMOf(cleanPoints()) / 1000.0

    /** 缓存清洗结果：points 只在结尾追加，size 变化时重算即可（统计页会反复取里程）。 */
    private var cleanCacheSize = -1
    private var cleanCache: List<TripPoint> = emptyList()

    /** 展示口径用的点：漂移刺头已剔除，原始 points 一个不动。 */
    fun cleanPoints(): List<TripPoint> {
        if (cleanCacheSize != points.size) {
            cleanCache = dropJumps(points)
            cleanCacheSize = points.size
        }
        return cleanCache
    }

    /** 距离到底来自哪一路——界面上要能说清，不能让他猜是 GPS 抖出来的还是真跑了这么多。 */
    val distanceFromOdometer: Boolean get() = odometerDeltaKm != null
}

/** 逐段哈弗辛距离（GPS 兜底口径）。进行中的行程 points 还在长，所以每次现算、不缓存。 */
fun distanceMOf(points: List<TripPoint>): Double {
    var sum = 0.0
    for (i in 1 until points.size) sum += haversineM(points[i - 1], points[i])
    return sum
}

/** 漂移判据：相邻采样点的"视速度"上限。60 m/s ≈ 216 km/h，公路上开不出来，只可能是定位漂移。 */
const val GPS_JUMP_MPS = 60.0

/** 小于这个腿长的三角形不参与离群判定——和 GPS 噪声同尺度，删了反而伤真实轨迹。 */
private const val DESPIKE_MIN_LEG_M = 70.0

private fun jumpMps(a: TripPoint, b: TripPoint): Double =
    haversineM(a, b) / ((b.t - a.t).coerceAtLeast(1L) / 1000.0)

/**
 * 单个点 P 相对两个参照点 A、B 是否离群。两个判据：
 * 1) "甩出去再拉回来"：P 离 A、B 都很远，而 A、B 彼此很近（首尾、驻车段、成片漂移的边缘都适用）；
 *    真实行驶点的 da+db ≈ ab，不可能 da > 3·ab。
 * 2) 折返角：P→A 与 P→B 的夹角小于 60°，等价于行进方向被掰回 120° 以上。
 *    10 秒一个采样点，正常道路弯道远到不了这个角度；真正的低速掉头腿长不够，被下限挡掉。
 */
private fun isSpike(p: TripPoint, a: TripPoint, b: TripPoint): Boolean {
    val da = haversineM(a, p)
    val db = haversineM(p, b)
    if (minOf(da, db) < DESPIKE_MIN_LEG_M) return false
    val ab = haversineM(a, b)
    // 路程合理性（两个判据共用）：真实道路点的两腿之和 ≈ 一个腿长时段的车距
    // （弯道把弦变短，只会更小）；漂移是"甩出去再拉回来"，两腿之和远超它。
    val dtSec = ((p.t - a.t).coerceAtLeast(1L) + (b.t - p.t).coerceAtLeast(1L)) / 2000.0
    val expected = maxOf(p.kmh, (a.kmh + b.kmh) / 2f).coerceAtLeast(0f) / 3.6 * dtSec
    val tooFar = da + db > expected * 1.6 + 60.0
    if (ab < 1.0) return tooFar
    // 判据1（甩出去拉回来）：da/db 都远大于 ab。**必须过速度护栏**——真实掉头/发卡弯的
    // 顶点在几何上与刺头同形（两腿长、弦短），但两腿之和不会超过该车速能跑出的距离；
    // 没有这道护栏，低速急弯的合法顶点会被误删（折线把弯抄直、GPS 里程少算）。
    if (da > 3 * ab && db > 3 * ab) return tooFar
    val cos = ((da * da + db * db - ab * ab) / (2 * da * db)).coerceIn(-1.0, 1.0)
    if (ab >= 0.75 * (da + db) || cos <= 0.5) return false
    return tooFar
}

/**
 * 端点离群：P 是首/尾点，只能拿它后面（或前面）两个点做参照。两个参照点彼此很近、
 * 而 P 离它们三倍以上远，就是刚起步/刚停车时那一下没收敛的定位。
 * 真实端点 d1≈参照间距、d2≈两倍参照间距，永远够不到 3 倍。
 */
private fun isEdgeSpike(p: TripPoint, r1: TripPoint, r2: TripPoint): Boolean {
    val d1 = haversineM(p, r1)
    val d2 = haversineM(p, r2)
    return d1 > DESPIKE_MIN_LEG_M && d2 > 3 * haversineM(r1, r2).coerceAtLeast(1.0)
}

/**
 * 画图/口径统一：剔除漂移刺头。只影响绘制与里程读数，不动已落盘的原始点——
 * 删数据是不可逆的，藏数据随时可以还原。
 *
 * 判据：视速度超限（最明显的跳点）→ 邻点折返（单点甩刺）→ 隔点定位（连续 2~3 个
 * 采样点都漂出去时，中间那个点离前后各隔一个点的参照点近得可疑）→ 端点离群。
 * 每轮从整条轨迹重新过一遍，直到没有变化，最多 4 轮。
 */
fun dropJumps(points: List<TripPoint>): List<TripPoint> {
    if (points.size == 2 && jumpMps(points[0], points[1]) > GPS_JUMP_MPS) {
        return points.take(1)
    }
    if (points.size < 3) return points
    var cur = points
    repeat(4) {
        val out = ArrayList<TripPoint>(cur.size)
        for (i in cur.indices) {
            val p = cur[i]
            val prev = cur.getOrNull(i - 1)
            val next = cur.getOrNull(i + 1)
            val fastSegment = (prev != null && jumpMps(prev, p) > GPS_JUMP_MPS) ||
                (next != null && jumpMps(p, next) > GPS_JUMP_MPS)
            val nearMiss = prev != null && next != null && isSpike(p, prev, next)
            val wideMiss = i >= 2 && i + 2 < cur.size && isSpike(p, cur[i - 2], cur[i + 2])
            val edgeMiss = when {
                i == 0 && cur.size >= 3 -> isEdgeSpike(p, cur[1], cur[2])
                i == cur.lastIndex && cur.size >= 3 -> isEdgeSpike(p, cur[i - 1], cur[i - 2])
                else -> false
            }
            if (!fastSegment && !nearMiss && !wideMiss && !edgeMiss) out.add(p)
        }
        if (out.size == cur.size) return out
        cur = out
        if (cur.size < 3) return cur
    }
    return cur
}

fun haversineM(a: TripPoint, b: TripPoint): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLng = Math.toRadians(b.lng - a.lng)
    val h = sin(dLat / 2).pow(2) +
        cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLng / 2).pow(2)
    return 2 * r * atan2(sqrt(h), sqrt(1 - h))
}

/** 服务区休息后可以接续的时间窗口：上一段结束到现在不超过 1 小时。 */
const val RESUME_WINDOW_MS = 60 * 60_000L

/**
 * 选出可以接续的上一段：最近一条已收尾的行程，且结束在窗口内。
 * 已经接续过的进行中行程不再提供——否则会继续把更早、互不相关的那一段也接进来；
 * [dismissedStart] 让用户点过"不用"的那一段不再冒出来。
 */
internal fun pickResumable(
    finished: List<Trip>,
    open: Trip?,
    now: Long,
    dismissedStart: Long? = null,
): Trip? {
    if (open?.continued == true) return null
    val prev = finished.firstOrNull() ?: return null
    if (prev.endedAt <= 0L || prev.points.size < 2) return null
    if (prev.startedAt == dismissedStart) return null
    if (now - prev.endedAt > RESUME_WINDOW_MS) return null
    return prev
}

/**
 * 把上一段的轨迹接到当前段前面，变成一趟完整行程：
 * 起点/开始里程取上一段，终点里程与峰值取两段里最新的那个。休息期间没有采样点，
 * 折线会直接连过去（同一停车位置，长度约等于 0），用时按真实经过时间算。
 */
internal fun mergePreviousIntoOpen(prev: Trip, open: Trip): Trip {
    open.startedAt = prev.startedAt
    open.points.addAll(0, prev.points)
    open.startOdoHm = prev.startOdoHm ?: open.startOdoHm
    open.endOdoHm = open.endOdoHm ?: prev.endOdoHm
    open.peakKmh = listOfNotNull(prev.peakKmh, open.peakKmh).maxOrNull()
    open.continued = true
    return open
}

/**
 * 行驶轨迹：BLE 的 LOCATION 读数采样，本地 JSON 持久化，不上任何服务器。
 * 断开/切页不打断行程——同一行程在内存里保持打开，直到停车满 3 分钟或超过 6 小时空档。
 */
class TripRecorder(filesDir: File, syncLoad: Boolean = false) {
    val trips = MutableStateFlow<List<Trip>>(emptyList())

    private val dir = File(filesDir, "trips").apply { mkdirs() }
    private val finished = mutableListOf<Trip>()
    private var open: Trip? = null

    /** 用户点过"不用"的那一段（按开始时间记），进行中或收尾变化后自然失效。 */
    private var dismissedStart: Long? = null

    /**
     * 所有磁盘 IO（启动加载、增量落盘、收尾清理）都走这条单线程队列：
     * ①启动加载不能在主线程同步读 200 个 JSON（冷启动 ANR 风险）；
     * ②GPS 回调在主线程，每 50s 构建几千点 JSON 会卡顿；
     * ③单写者天然保序，persist 与"写新删旧"不会交错。
     * 内存结构的修改仍发生在调用线程，但统一持 [lock] 防止加载与采样竞争。
     */
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "trip-io").apply { isDaemon = true }
    }
    private val lock = Any()

    init {
        // syncLoad 仅测试用：正式路径必须异步——Application 主线程同步读 200 个 JSON 会卡冷启动。
        if (syncLoad) load() else io.execute { synchronized(lock) { load() } }
    }

    fun hasOpenTrip(): Boolean = synchronized(lock) { open != null }

    /**
     * 车速道每帧喂一次：峰值要 250ms 粒度才抓得住短促的加速，
     * GPS 点 10s 一个，只按点算最高速会明显偏低。没有进行中的行程就直接返回。
     */
    fun onSpeed(kmh: Float) {
        val trip = open ?: return
        val peak = trip.peakKmh
        if (peak == null || kmh > peak) trip.peakKmh = kmh
    }

    fun add(p: TripPoint, odoHm: Int? = null) {
        var persistTrip: Trip? = null
        var persistSnapshot: List<TripPoint>? = null
        synchronized(lock) {
            open?.let { trip ->
                val last = trip.points.lastOrNull()
                if (last != null && p.t - last.t > GAP_MS) finish(last.t, trip.endOdoHm)
            }
            val trip = open ?: Trip(startedAt = p.t).also { open = it }
            // 漂移刺头就地拒收：相邻两点按时间差折算的视速度超过公路上限只可能是漂移
            // （隧道口、高架桥下、停车楼的多路径反射）。拒收只丢这个定位点，里程计照常更新；
            // 这是数据清洗不是故障，写一行日志备查即可，不上界面。
            val last = trip.points.lastOrNull()
            if (last != null && jumpMps(last, p) > GPS_JUMP_MPS) {
                com.teslalink.app.debug.DebugLog.log(
                    "trip", "丢弃漂移定位点：视速度 ${"%.0f".format(jumpMps(last, p))} m/s",
                )
            } else if (trip.points.size < MAX_POINTS) {
                trip.points.add(p)
                // 每 5 个点（约 50 秒）落一次盘：以前只在收尾时写文件，进程半路死掉这段行程就整条没了
                // ——他实测的"闪退后本次行驶数据、轨迹丢失"就是这个。写盘失败不影响继续采样。
                if (trip.points.size % PERSIST_EVERY == 0) {
                    persistTrip = trip
                    persistSnapshot = ArrayList(trip.points)
                }
            }
            if (odoHm != null) {
                if (trip.startOdoHm == null) trip.startOdoHm = odoHm
                trip.endOdoHm = odoHm
            }
            publish()
        }
        if (persistTrip != null && persistSnapshot != null) {
            // 落盘走 IO 队列：主线程只做内存操作，几千点的 JSON 构建不卡 GPS 回调。
            // 快照在持锁时拍，IO 线程构建 JSON 不会与采样并发迭代同一个列表。
            val trip = persistTrip
            val snapshot = persistSnapshot
            io.execute { synchronized(lock) { persist(trip, snapshot) } }
        }
    }

    fun finish(t: Long, odoHm: Int? = null) {
        var toPersist: Trip? = null
        synchronized(lock) {
            val trip = open ?: return
            open = null
            trip.endedAt = t
            if (odoHm != null) {
                if (trip.startOdoHm == null) trip.startOdoHm = odoHm
                trip.endOdoHm = odoHm
            }
            if (trip.points.size >= 2) {
                finished.add(0, trip)
                toPersist = trip
            }
            publish()
        }
        toPersist?.let { trip ->
            io.execute {
                synchronized(lock) {
                    persist(trip)
                    prune()
                }
            }
        }
    }

    /**
     * 删一条轨迹，连本地 JSON 一起删。进行中的那条也能删：丢掉当前这段，
     * 下一个采样点自然开一段新轨迹（所以界面要把"进行中"标出来，别让他以为删不掉）。
     */
    fun delete(trip: Trip): Boolean {
        var removed: Boolean
        synchronized(lock) {
            removed = if (open === trip) {
                open = null
                true
            } else {
                finished.remove(trip)
            }
            if (!removed) return false
            publish()
        }
        io.execute { runCatching { File(dir, "trip_${trip.startedAt}.json").delete() } }
        return true
    }

    /**
     * 仪表盘浮层用：现在是否该提供"接续上一段路程"。
     * 判据只有一条时间窗（上一段结束在 1 小时内）；"已经接续过/用户点过不用"在 helper 里挡掉。
     */
    fun resumable(now: Long = System.currentTimeMillis()): Trip? =
        synchronized(lock) { pickResumable(finished, open, now, dismissedStart) }

    /** 用户点"不用"：这一段不再提示，直到有更新的一段收尾。只影响提示，不动任何轨迹数据。 */
    fun dismissResume() {
        synchronized(lock) { dismissedStart = pickResumable(finished, open, now = System.currentTimeMillis())?.startedAt ?: return }
    }

    /**
     * 接续上一段：有进行中的行程就把上一段并进来，没有就把上一段重新打开继续写。
     * 返回接续后的行程；null = 候选已超出 1 小时窗口（浮层可能还停在屏幕上）。
     */
    fun continuePrevious(): Trip? {
        var oldFile: File? = null
        var merged: Trip? = null
        synchronized(lock) {
            val prev = resumable() ?: return null
            finished.remove(prev)
            dismissedStart = null
            val cur = open
            if (cur == null) {
                prev.endedAt = 0
                prev.continued = true
                open = prev
                merged = prev
                publish()
                return prev
            }
            oldFile = File(dir, "trip_${cur.startedAt}.json")
            val m = mergePreviousIntoOpen(prev, cur)
            merged = m
            // 合并后文件名就是上一段的（起点更早）。写成功才删旧文件——写失败时两份原始数据都留着。
            // 磁盘部分交给 IO 队列顺序执行；进程死在中间最坏留两份重复轨迹（数据不丢）。
            val old = oldFile
            io.execute {
                synchronized(lock) {
                    if (persist(m)) {
                        runCatching {
                            if (old.path != File(dir, "trip_${m.startedAt}.json").path) old.delete()
                        }
                    }
                }
            }
            publish()
        }
        return merged
    }

    private fun publish() {
        trips.value = listOfNotNull(open) + finished
    }

    /**
     * 启动时把盘上的行程读回来（IO 队列线程，见 init）。**没收尾的那条要能续上**：
     * 上次进程被杀/闪退时它已经增量落盘，直接丢掉就等于白跑一段路。
     * 判据按停车收尾的同一条规矩：最后一个采样点在 3 分钟内 → 恢复成 open 继续写；
     * 3 分钟～6 小时 → 说明停车后没再开出去，就地收尾（旧实现会让它整场挂"进行中"）；
     * 超过 6 小时也是就地收尾。
     */
    private fun load() {
        var resumed = false
        dir.listFiles { f -> f.name.startsWith("trip_") && f.name.endsWith(".json") }
            ?.sortedByDescending { it.name }
            ?.take(KEEP_TRIPS)
            ?.forEach { f ->
                val trip = runCatching { parseTrip(f.readText()) }.getOrNull() ?: return@forEach
                if (trip.endedAt == 0L && trip.points.isNotEmpty()) {
                    val last = trip.points.last().t
                    val gap = System.currentTimeMillis() - last
                    if (!resumed && gap < GAP_MS) {
                        if (gap < PARK_TO_END_MS) {
                            open = trip
                            resumed = true
                            com.teslalink.app.debug.DebugLog.log(
                                "trip",
                                "续上上次未收尾的行程：${trip.points.size} 个点，起点 ${java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(trip.startedAt))}",
                            )
                            return@forEach
                        }
                        // 停车早已满 3 分钟：按收尾规则就地结束，不再挂着"进行中"。
                        trip.endedAt = last
                        persist(trip)
                        finished.add(trip)
                        return@forEach
                    }
                    trip.endedAt = last
                    persist(trip)
                }
                finished.add(trip)
            }
        publish()
    }

    /**
     * 整段都要包起来：`JSONObject.put` 遇到 NaN/Infinity 会抛 JSONException，磁盘满/writeText
     * 也会抛。以前这些异常从 finish() 一路冒到车道协程外面 = 整个进程没了，而且**这条行程
     * 永远没被写过盘**，正好就是他看到的"闪退后本次行驶数据、轨迹丢失"。
     *
     * [points] 由调用方在持锁时拍快照传入（增量落盘路径），IO 线程构建 JSON 不与采样并发迭代。
     *
     * **原子写**：先写 `.tmp` 再 rename——直接覆盖目标文件时进程死在写一半会留下截断 JSON，
     * load 时 parse 失败被 runCatching 吞掉 = 整条行程（含此前增量落盘的点）全丢，
     * 恰好击穿增量落盘要防的那个场景。
     */
    private fun persist(trip: Trip, points: List<TripPoint> = trip.points): Boolean {
        return runCatching {
            val json = JSONObject()
                .put("startedAt", trip.startedAt)
                .put("endedAt", trip.endedAt)
                .put("startOdoHm", trip.startOdoHm ?: JSONObject.NULL)
                .put("endOdoHm", trip.endOdoHm ?: JSONObject.NULL)
                .put("peakKmh", trip.peakKmh?.takeIf { it.isFinite() }?.toDouble() ?: JSONObject.NULL)
                .put("continued", trip.continued)
                .put(
                    "points",
                    JSONArray().apply {
                        points.forEach { p ->
                            put(
                                JSONObject()
                                    .put("t", p.t)
                                    .put("lat", p.lat)
                                    .put("lng", p.lng)
                                    .put("h", p.heading ?: -1)
                                    .put("v", if (p.kmh.isFinite()) p.kmh.toDouble() else 0.0),
                            )
                        }
                    },
                )
            val dest = File(dir, "trip_${trip.startedAt}.json")
            val tmp = File(dir, "trip_${trip.startedAt}.json.tmp")
            tmp.writeText(json.toString())
            if (!tmp.renameTo(dest)) {
                // 同目录 rename 极少失败（Windows 挂载/异常 FS）：退回逐字节复制，仍不覆盖到一半。
                dest.outputStream().use { out -> tmp.inputStream().use { it.copyTo(out) } }
                tmp.delete()
            }
        }.onFailure {
            runCatching { File(dir, "trip_${trip.startedAt}.json.tmp").delete() }
            com.teslalink.app.debug.DebugLog.error("trip", "行程落盘失败（继续采样，不影响记录）：${it.message}")
        }.isSuccess
    }

    private fun prune() {
        val files = dir.listFiles { f -> f.name.startsWith("trip_") && f.name.endsWith(".json") }
            ?.sortedByDescending { it.name } ?: return
        files.drop(KEEP_TRIPS).forEach { runCatching { it.delete() } }
    }

    private fun parseTrip(text: String): Trip {
        val obj = JSONObject(text)
        // 老文件没有这两个键 → null → 本次里程自动回落到 GPS 累加。
        fun odo(key: String): Int? =
            if (!obj.has(key) || obj.isNull(key)) null else obj.optInt(key).takeIf { it > 0 }
        val trip = Trip(
            obj.getLong("startedAt"),
            obj.optLong("endedAt"),
            startOdoHm = odo("startOdoHm"),
            endOdoHm = odo("endOdoHm"),
            peakKmh = if (obj.has("peakKmh") && !obj.isNull("peakKmh")) obj.optDouble("peakKmh").toFloat()
                else null,
            continued = obj.optBoolean("continued", false),
        )
        val arr = obj.getJSONArray("points")
        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i)
            val h = p.optInt("h", -1)
            trip.points.add(
                TripPoint(p.getLong("t"), p.getDouble("lat"), p.getDouble("lng"), if (h < 0) null else h, p.getDouble("v").toFloat())
            )
        }
        return trip
    }

    companion object {
        /** 10 s 采样 × 20 小时；一条轨迹 JSON 通常只有几十 KB，磁盘上可以大方地多留。 */
        const val MAX_POINTS = 7_200
        const val KEEP_TRIPS = 200

        /** 同一行程的判定：采样空档超过 6 小时就算新行程。 */
        const val GAP_MS = 6 * 60 * 60_000L

        /** 停车满 3 分钟收尾当前行程。 */
        const val PARK_TO_END_MS = 3 * 60_000L

        /** 每多少个采样点增量落一次盘（10s/点 → 约 50 秒一次，一条行程几十 KB，写盘开销可忽略）。 */
        private const val PERSIST_EVERY = 5
    }
}
