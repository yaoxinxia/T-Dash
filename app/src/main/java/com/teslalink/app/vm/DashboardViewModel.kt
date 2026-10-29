package com.teslalink.app.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tesla.generated.carserver.vehicle.Vehicle
import com.teslalink.app.AppContainer
import com.teslalink.app.debug.DebugLog
import com.teslalink.app.vehicle.Payloads
import com.teslalink.app.vehicle.VehicleConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val WEATHER_REFRESH_MS = 30 * 60_000L
private const val WEATHER_RETRY_MS = 10 * 60_000L

internal data class DriveSample(val speedKmh: Float? = null, val shift: String? = null)

/** Only fields actually present in this response count as new evidence. */
internal fun parseDriveSample(data: Vehicle.VehicleData?): DriveSample {
    if (data == null || !data.hasDriveState()) return DriveSample()
    val drive = data.driveState
    return DriveSample(
        speedKmh = when {
            drive.hasSpeedFloat() -> drive.speedFloat * 1.609344f
            drive.hasSpeed() -> drive.speed * 1.609344f
            else -> null
        }?.takeIf { it.isFinite() },
        shift = when (drive.shiftState.typeCase) {
            Vehicle.ShiftState.TypeCase.P -> "P"
            Vehicle.ShiftState.TypeCase.R -> "R"
            Vehicle.ShiftState.TypeCase.N -> "N"
            Vehicle.ShiftState.TypeCase.D -> "D"
            else -> null
        },
    )
}

internal fun readingIsFresh(at: Long?, now: Long, maxAgeMs: Long = 5_000L): Boolean =
    at != null && now - at in 0 until maxAgeMs

/** Field-specific clocks: a speed-only frame cannot freshen an old P (or D). */
internal data class DriveReadings(
    val speedKmh: Float? = null,
    val shift: String? = null,
    val speedAtMs: Long? = null,
    val shiftAtMs: Long? = null,
) {
    fun updated(sample: DriveSample, now: Long): DriveReadings = copy(
        speedKmh = sample.speedKmh ?: speedKmh,
        shift = sample.shift ?: shift,
        speedAtMs = if (sample.speedKmh != null) now else speedAtMs,
        shiftAtMs = if (sample.shift != null) now else shiftAtMs,
    )

    fun moving(now: Long, maxAgeMs: Long = 5_000L): Boolean =
        (readingIsFresh(speedAtMs, now, maxAgeMs) && kotlin.math.abs(speedKmh ?: 0f) > 0.5f) ||
            (readingIsFresh(shiftAtMs, now, maxAgeMs) && (shift == "D" || shift == "R"))

    fun allowsSlowRead(now: Long, drivingUntilMs: Long): Boolean =
        drivingUntilMs <= now && readingIsFresh(speedAtMs, now) && readingIsFresh(shiftAtMs, now) &&
            speedKmh != null && kotlin.math.abs(speedKmh) <= 0.5f && (shift == "P" || shift == "N")
}

internal fun nextDrivingUntil(previous: Long, sample: DriveSample, now: Long): Long = when {
    kotlin.math.abs(sample.speedKmh ?: 0f) > 0.5f || sample.shift == "D" || sample.shift == "R" -> now + 90_000L
    // Clear only on an explicit low-speed P/N frame, never on a carried-over UI value.
    sample.speedKmh != null && kotlin.math.abs(sample.speedKmh) <= 0.5f &&
        (sample.shift == "P" || sample.shift == "N") -> 0L
    else -> previous
}

internal fun applyDashboardDrive(
    previous: DashboardViewModel.ClusterState,
    data: Vehicle.VehicleData?,
    now: Long,
): DashboardViewModel.ClusterState {
    if (data == null || !data.hasDriveState()) return previous
    val drive = data.driveState
    val sample = parseDriveSample(data)
    return previous.copy(
        speedKmh = sample.speedKmh ?: previous.speedKmh,
        shift = sample.shift ?: previous.shift,
        powerKw = if (drive.hasPower()) drive.power else previous.powerKw,
        odometerKm = if (drive.hasOdometerInHundredthsOfAMile()) {
            drive.odometerInHundredthsOfAMile * 0.0160934f
        } else previous.odometerKm,
        odometerHm = if (drive.hasOdometerInHundredthsOfAMile()) {
            drive.odometerInHundredthsOfAMile
        } else previous.odometerHm,
        // The cluster's stale indicator represents speed, not an unrelated power/shift update.
        updatedAtMs = if (sample.speedKmh != null) now else previous.updatedAtMs,
    )
}

/**
 * 车速道节奏。行驶：跟着真实往返走（下限 250ms，一次 250ms 的读排在别人的响应后面会直接翻倍）。
 * 驻车/证据还没有：**固定 600ms，保证至少 1 秒一次**，而且不看车机会不会答——
 * 链路是不是刚活过来只有敲一次才知道，等到 5 秒就是"起步后头几秒没有速度"。
 */
internal fun fastLaneInterval(moving: Boolean, lastRttMs: Long, parkedMs: Long = 600L): Long =
    if (moving) (lastRttMs + 60L).coerceIn(250L, 500L) else parkedMs

/**
 * 一次 vehicleData 的总预算（排锁+握手+响应一起算，见 VehicleConnection.withReadDeadline）。
 * 驻车必须 ≤1000ms：车机睡着时这条读会一直挂到超时，预算就是实际节奏——2.5s 的预算等于
 * 0.4Hz，直接违背"P 档至少 1 秒一次"。行驶留 2.5s 是为了偶尔一次慢往返别把闩打断。
 */
internal fun fastLaneBudgetMs(moving: Boolean): Long = if (moving) 2_500L else 1_000L

/**
 * 仪表慢道（CHARGE/CLIMATE）的单类原子读预算。**必须小于驻车车速读的 1 秒**，
 * 否则一次慢类读就能把 1Hz 节奏挤掉一轮；车机醒着时这两类普遍 200–400ms 回完。
 */
internal const val SLOW_READ_BUDGET_MS = 700L

/** Failure and slow-success logs have separate, per-category 10s throttles. */
internal class TelemetryReadLog(private val tag: String) {
    private val lastAt = mutableMapOf<String, Long>()

    fun record(category: String, started: Long, failure: String? = null) {
        val now = System.currentTimeMillis()
        val elapsed = now - started
        val outcome = if (failure != null) "失败" else if (elapsed >= 800L) "慢请求" else return
        val key = "$category/$outcome"
        if (lastAt[key]?.let { now - it < 10_000L } == true) return
        lastAt[key] = now
        DebugLog.log(tag, "$category $outcome ${elapsed}ms${failure?.let { "：$it" }.orEmpty()}")
    }
}

/**
 * Feeds the instrument cluster over BLE with two polling lanes:
 *  - fast lane (DRIVE: speed/power/shift/odometer) at ~250 ms while moving;
 *  - slow lane (CHARGE+CLIMATE: soc/range/charge/temp) every ~2.5 s.
 */
class DashboardViewModel(private val c: AppContainer) : ViewModel() {

    data class Point(val t: Long, val v: Float)

    /** Latest telemetry snapshot for the instrument cluster. */
    data class ClusterState(
        val speedKmh: Float = 0f,
        val powerKw: Int = 0,
        /** 零百成绩里显示 SOC 起点/终点用（仪表上已无电量圈）。 */
        val soc: Int? = null,
        val shift: String? = null,
        val odometerKm: Float? = null,
        /**
         * 里程计原始值（0.01 英里一格）。本次里程用它的**差值**：整数相减没有浮点抵消误差，
         * 而 Float 存 10 万公里的总里程时末位已经有几米的不确定度，再相减就把量化误差放大了。
         */
        val odometerHm: Int? = null,
        /** 车外温度：只在驻车时读（CLIMATE 类），行驶中不发这个请求，驻车版面才显示。 */
        val outsideTemp: Float? = null,
        /** 驻车版面「空调」卡的三个值。行驶中不发 CLIMATE，保持最后一次驻车读到的旧值。 */
        val driverTemp: Float? = null,
        val insideTemp: Float? = null,
        val climateOn: Boolean? = null,
        val chargePowerKw: Int? = null,
        val charging: Boolean = false,
        val updatedAtMs: Long = 0,
    )

    val cluster = MutableStateFlow(ClusterState())

    /** Head-unit session state, mirrored for the cluster's telltale row. */
    val mcuReady: StateFlow<Boolean> = c.connection.mcuSessionReady

    /**
     * 速度分档与颜色：设置页可改。连发（按住加减）时每秒会改十几次，
     * 这里只更新内存值，落盘由 init 里的 collectLatest 合并——松手 250ms 后写一次。
     */
    val speedBands = MutableStateFlow(SpeedBands())

    fun setSpeedBands(bands: SpeedBands) {
        val normalized = bands.normalized()
        if (normalized != speedBands.value) speedBands.value = normalized
    }

    val speed = MutableStateFlow<List<Point>>(emptyList())
    val power = MutableStateFlow<List<Point>>(emptyList())

    private var fastJob: Job? = null

    // ---- 零百计时（与仪表联动：计时中仪表实时显示秒表） ----

    /** 一次进行中的测试：armedAt 时刻待命，起步过 0.5 km/h 记 t0，过 60/100 记分段。 */
    data class AccelRun(
        val armedAt: Long,
        val t0Ms: Long? = null,
        val t60Ms: Long? = null,
        val t100Ms: Long? = null,
        val socStart: Int? = null,
        val maxPowerKw: Int = 0,
    )

    data class AccelResult(
        val t0Ms: Long,
        val t0To60Ms: Long?,
        val t0To100Ms: Long?,
        val t60To100Ms: Long?,
        val maxPowerKw: Int,
        val socStart: Int?,
        val socEnd: Int?,
    )

    val accelRun = MutableStateFlow<AccelRun?>(null)
    val accelResults = MutableStateFlow<List<AccelResult>>(emptyList())
    private var pendingAccelSocResult: AccelResult? = null

    /** 超时/作废原因：必须就地显示在工具页（旧实现静默把按钮跳回"开始"，他不知道为什么）。 */
    val accelNote = MutableStateFlow<String?>(null)

    @Volatile private var lastAccelSpeed = 0f
    @Volatile private var lastAccelTick = 0L

    fun armAccel() {
        lastAccelSpeed = cluster.value.speedKmh
        // Arming is not a speed sample: do not stamp a frozen UI value as fresh.
        lastAccelTick = driveReadings.speedAtMs ?: 0L
        accelNote.value = null
        accelRun.value = AccelRun(armedAt = System.currentTimeMillis())
    }

    fun cancelAccel() {
        accelRun.value = null
        accelNote.value = null
    }

    /** Timeouts still run when DRIVE is missing, without inventing a speed sample. */
    private fun expireAccel(now: Long) {
        val run = accelRun.value ?: return
        val note = when {
            run.t0Ms == null && now - run.armedAt > 120_000L ->
                "已自动作废：待命 120 秒没有起步（车速没过 0.5 km/h）。再点一次「开始零百测试」重新待命。"
            run.t0Ms != null && now - run.t0Ms > 60_000L ->
                "已自动作废：起步后 60 秒没有跑到 100 km/h（车速流中断或场地不够长）。"
            else -> null
        }
        if (note != null) {
            accelRun.value = null
            accelNote.value = note
        }
    }

    /**
     * 车速流的 crossing 检测：0.5 / 60 / 100 km/h 三个阈值都做线性插值，
     * 时间精度高于 250 ms 的采样节奏。待命 120 s 未起步、起步 60 s 未到 100 自动作废。
     */
    private fun evaluateAccel(now: Long, kmh: Float) {
        val run = accelRun.value ?: return
        val prev = lastAccelSpeed
        val prevT = lastAccelTick
        lastAccelSpeed = kmh
        lastAccelTick = now

        fun cross(target: Float): Long? {
            if (prev < target && kmh >= target && now > prevT) {
                val frac = ((target - prev) / (kmh - prev)).coerceIn(0f, 1f)
                return prevT + ((now - prevT) * frac).toLong()
            }
            return null
        }

        // 轮询停摆（切页/断连/熄屏）后恢复的第一帧：与上一个样本隔得太久，插值基线已失效。
        // 不重置的话 cross(0.5) 会把 t0 插值到几小时前，记出一条"600 秒"的荒谬成绩。
        if (now - prevT > 5_000L) return

        if (run.t0Ms == null) {
            val t0 = cross(0.5f)
            if (t0 != null) accelRun.value = run.copy(t0Ms = t0, socStart = cluster.value.soc)
            return
        }
        val t0 = run.t0Ms
        var updated = run.copy(maxPowerKw = maxOf(run.maxPowerKw, cluster.value.powerKw))
        if (updated.t60Ms == null) cross(60f)?.let { updated = updated.copy(t60Ms = it) }
        val t100 = cross(100f)
        if (t100 != null) {
            val result = AccelResult(
                t0Ms = t0,
                t0To60Ms = updated.t60Ms?.let { it - t0 },
                t0To100Ms = t100 - t0,
                t60To100Ms = updated.t60Ms?.let { t100 - it },
                maxPowerKw = updated.maxPowerKw,
                socStart = updated.socStart,
                socEnd = null,
            )
            accelResults.value = (listOf(result) + accelResults.value).take(20)
            accelRun.value = null
            // 100 km/h 时也不能插入 CHARGE。终点 SOC 留空，明确驻车后的首个电量读数再补，
            // 而不是拿出发前的缓存伪装成终点读数；连续测试只补最近一条。
            pendingAccelSocResult = result
            return
        }
        accelRun.value = updated
    }
    private var slowJob: Job? = null
    private var locationJob: Job? = null

    /** 行驶轨迹（只在「仪表/统计」页可见时采样，见 locationLane）。 */
    val trips: StateFlow<List<Trip>> = c.trips.trips

    /** 删掉一条轨迹（含本地 JSON）；false = 这条已经不在了。 */
    fun deleteTrip(trip: Trip): Boolean = c.trips.delete(trip)

    /** 仪表浮层：上一段结束在 1 小时内就提供"接续上一段路程"。 */
    fun resumableTrip(now: Long = System.currentTimeMillis()): Trip? = c.trips.resumable(now)

    fun continuePreviousTrip(): Trip? = c.trips.continuePrevious()

    fun dismissResumeOffer() = c.trips.dismissResume()

    private var lastMovingAt = 0L

    /** 内部缓冲：250ms 节奏只写 ArrayList（O(1) 摊还），快照按 1Hz 发给统计页。 */
    private val speedSeries = ArrayList<Point>(4096)
    private val powerSeries = ArrayList<Point>(4096)
    private var lastSeriesPublishMs = 0L

    private fun recordSeries(series: ArrayList<Point>, point: Point, now: Long) {
        // 本次启动内全量保留（他 2026-10-13 要求）；渲染侧在 LineChart 里抽稀，数据不丢。
        series.add(point)
    }

    /** 统计页只读快照：整表复制的成本按 1Hz 付，而不是 4Hz——行程越长它越是卡顿放大器。 */
    private fun publishSeries(now: Long) {
        if (now - lastSeriesPublishMs < 1_000) return
        lastSeriesPublishMs = now
        speed.value = speedSeries.toList()
        power.value = powerSeries.toList()
    }

    /**
     * Telemetry polling only runs while the link is usable and nobody else owns it: before the
     * vehicle recognises our key every attempt is a failing handshake, and during a manual command
     * or the self-test we'd only be adding queue latency to their measurement.
     *
     * 门控 = 前台 **或 行驶闩**（他 2026-10-24 定的口径）：D/R 时息屏/后台必须继续监听
     * ——手机就是仪表，轨迹也不能断；只有 P/N 才允许停。行驶闩由车速道用**新鲜**数据维护
     * （见 fastLane），断链后 90s 自动过期，不会拿着冻结的 D 档无限轮询。
     */
    private fun pollable(): Boolean =
        (c.appInForeground.value || c.drivingUntilMs.value > System.currentTimeMillis()) &&
            c.connection.state.value == VehicleConnection.State.CONNECTED &&
            c.connection.keyPaired.value &&
            !c.connection.exclusive.value

    init {
        viewModelScope.launch {
            speedBands.value = SpeedBands.decode(c.prefs.getString("speed_bands"))
        }
        // 用户停下 250ms 才落盘：连发时每个值都写一次会把 DataStore 打满，而丢掉中间值没影响。
        viewModelScope.launch {
            speedBands.drop(1).collectLatest { bands ->
                delay(250)
                c.prefs.putString("speed_bands", bands.encode())
            }
        }
        // A dropped link must not leave the last speed/SOC on screen: the cluster would keep
        // showing values from a car we are no longer talking to, and "行驶中" would stick.
        viewModelScope.launch {
            c.connection.state.collect { state ->
                if (state == VehicleConnection.State.DISCONNECTED) {
                    cluster.value = ClusterState()
                    driveReadings = DriveReadings()
                    pendingAccelSocResult = null
                    c.vehicleMoving.value = false
                }
            }
        }
    }

    private var driveReadings = DriveReadings()
    @Volatile private var driveReadInFlight = false
    @Volatile private var slowReadInFlight = false
    private val readLog = TelemetryReadLog("dashboard-read")

    private fun canReadParked(): Boolean = pollable() && !c.vehicleMoving.value &&
        driveReadings.allowsSlowRead(System.currentTimeMillis(), c.drivingUntilMs.value)

    /**
     * Each lane has at most one pending read, and every read is **one category** (an atomic
     * request): a multi-category reply exceeds the negotiated MTU, and long requests are what
     * used to starve the speed lane. Recheck parking at dispatch so auxiliary SOC reads cannot
     * bypass the slow-lane gate; the drive budget is what enforces "≥1 poll per second in P".
     */
    private suspend fun readVehicleData(category: Payloads.Category): Vehicle.VehicleData? {
        val drive = category == Payloads.Category.DRIVE
        if (drive) {
            if (driveReadInFlight) return null
            driveReadInFlight = true
        } else {
            if (slowReadInFlight || !canReadParked()) return null
            slowReadInFlight = true
        }
        val budgetMs = if (drive) {
            fastLaneBudgetMs(driveReadings.moving(System.currentTimeMillis()))
        } else {
            SLOW_READ_BUDGET_MS
        }
        val started = System.currentTimeMillis()
        return try {
            c.connection.vehicleData(
                listOf(category), attempts = 1, timeoutMs = budgetMs, wake = false,
                // 车速道拿到过帧 = 正在用它，唤醒探针据此收手（车速新鲜度压倒一切）。
                telemetry = drive,
            ).also { readLog.record(category.name, started) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            readLog.record(category.name, started, "${e.javaClass.simpleName}: ${e.message}")
            null
        } finally {
            if (drive) driveReadInFlight = false else slowReadInFlight = false
        }
    }

    /**
     * Ref-counted: 仪表 and 统计 share this VM, and on a tab switch the outgoing onDispose can
     * run after the incoming effect — without counting, the switch would silently kill polling.
     */
    private var pollingRefs = 0

    fun startBlePolling() {
        if (++pollingRefs > 1) return
        ensureLanes()
        // 朝向格的兜底罗盘跟页面走：仪表/统计可见时才采样，离开即注销。
        startHeadingSensor()
    }

    /**
     * 车道的生死判据：有页面要（引用计数）**或行驶闩还热**。这就是"行驶中的 vehicle 轮询是
     * 守护进程"的落点：D 档下切任何页面、甚至停在别的页，车速道照常跑，直到 P/N 让闩过期
     * 才自然收工。车道退出走"本轮跑完、下一轮条件不满足"，**从不 cancel()**——行驶中一条
     * in-flight 的 vehicleData 若被取消在 BleTransport.send 的写入窗口里，withGattCleanup
     * 会把整条 GATT 退役（表现正是"切页后蓝牙断联几秒又自动重连"）。
     */
    private fun lanesWanted(): Boolean =
        pollingRefs > 0 || c.drivingUntilMs.value > System.currentTimeMillis()

    private fun ensureLanes() {
        if (fastJob?.isActive == true) return
        // 车道自己抛异常时整条重启，而不是让协程把进程带走（见 laneTick）。
        fastJob = viewModelScope.launch { while (lanesWanted()) laneTick("车速", ::fastLane) }
        slowJob = viewModelScope.launch { while (lanesWanted()) laneTick("驻车读数", ::slowLane) }
        locationJob = viewModelScope.launch { while (lanesWanted()) laneTick("轨迹", ::locationLane) }
    }

    fun stopBlePolling() {
        if (pollingRefs > 0) pollingRefs--
        if (pollingRefs > 0) return
        // 故意不 cancel：车道在本轮结束后自查 lanesWanted()（行驶闩热着就继续跑）。
        // GPS 由 locationLane 自己启停（行驶闩热时轨迹照样记），这里不再动它。
        stopHeadingSensor()
    }

    /**
     * 车道的一轮。协程里未捕获的异常会直接带走整个进程（viewModelScope 只是不连累兄弟作业），
     * 表现就是"蓝牙一连上就闪退、本次行程没了"。所以：除取消之外什么都接住，写进日志，
     * 下一轮照常跑——仪表宁可某一格少刷一次，也不能整页消失。
     */
    private suspend fun laneTick(name: String, body: suspend () -> Unit) {
        try {
            body()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            com.teslalink.app.debug.DebugLog.error(
                "lane", "$name 本轮异常，已跳过（app 继续运行）：${e.javaClass.name}: ${e.message}", e,
            )
            delay(1_000L)
        }
    }

    private var lastFastRtt = 0L

    /**
     * Fast lane: DRIVE-only. Target cadence is measured from tick start and follows the real
     * round trip while moving（下限 250 ms）：一次 250 ms 的读排在别人的响应后面会直接翻倍。
     * 驻车（P/N 或证据还没有）**也必须至少 1 秒一次**：车机睡着时照样按节奏发——链路是不是
     * 刚活过来，只有车速道自己问一次才知道，等 5 秒就是"起步后前几秒没有速度"。
     */
    private suspend fun fastLane() {
        while (currentCoroutineContext().isActive) {
            val tickStarted = System.currentTimeMillis()
            val interval = fastLaneInterval(driveReadings.moving(tickStarted), lastFastRtt)
            if (pollable()) {
                val started = System.currentTimeMillis()
                // 车速道禁 wake（wake=false）：车机睡着时"唤醒+30s 重试"会持锁冻死 250ms 节奏，
                // 宁可这轮失败下一轮再来。行驶信号要带新鲜度（见下），车机睡着会自动熄灭。
                val data = readVehicleData(Payloads.Category.DRIVE)
                val now = System.currentTimeMillis()
                if (data != null) lastFastRtt = now - started
                val sample = parseDriveSample(data)
                if (data != null && sample.speedKmh == null && sample.shift == null) {
                    readLog.record("DRIVE", started, "响应缺少有效速度/档位，保留旧值但不续新鲜度")
                }
                // Publish the new frame BEFORE the latch, acceleration crossings and trip maximum.
                // Empty/non-DRIVE replies retain the UI but renew neither field's evidence clock.
                cluster.update { applyDashboardDrive(it, data, now) }
                driveReadings = driveReadings.updated(sample, now)
                c.drivingUntilMs.value = nextDrivingUntil(c.drivingUntilMs.value, sample, now)
                c.vehicleMoving.value = readingIsFresh(driveReadings.speedAtMs, now) &&
                    kotlin.math.abs(driveReadings.speedKmh ?: 0f) > 2f
                expireAccel(now)
                sample.speedKmh?.let { kmh ->
                    recordSeries(speedSeries, Point(now, kmh), now)
                    if (accelRun.value != null) evaluateAccel(now, kmh)
                    c.trips.onSpeed(kmh)
                }
                if (data?.hasDriveState() == true && data.driveState.hasPower()) {
                    recordSeries(powerSeries, Point(now, data.driveState.power.toFloat()), now)
                }
                publishSeries(now)
            }
            // Pace from tick start: a ~200 ms round trip inside a 250 ms cadence must not turn
            // the speedometer into a 2 Hz one.
            val elapsed = System.currentTimeMillis() - tickStarted
            delay((interval - elapsed).coerceAtLeast(50L))
        }
    }

    /**
     * Slow lane: CHARGE + CLIMATE，各一次请求（一次带多类会超协商 MTU（247），车辆回
     * RESPONSE_MTU_EXCEEDED，什么都拿不到）。
     * 停车：2.5s 正常节奏（车机不可达时 10s 慢探）。
     * 行驶：**一条请求都不发**。车速新鲜度第一是死规矩，而 infotainmentMutex 串行一切车机域
     * 对话——quick 读失败要吃满 2.5s 超时，每 90s 两次就是最多 5s 的数字冻结，不值。
     *
     * 这两类喂的是驻车态读数（驻车版面的电量/车外/空调卡 + 零百 SOC）：
     * 驻车版面只跟档位走，行驶中不可见，所以行驶中省掉它们没有代价。
     */
    private suspend fun slowLane() {
        while (currentCoroutineContext().isActive) {
            if (canReadParked()) {
                val mcuReady = c.connection.mcuSessionReady.value
                // Both categories recheck fresh P/N + low speed + the shared driving latch at
                // dispatch. All reads are quick/no-wake; no 15s × 3 fallback, even without a session.
                readVehicleData(Payloads.Category.CHARGE)?.let { applyCharge(it) }
                readVehicleData(Payloads.Category.CLIMATE)?.let { data ->
                    if (data.hasClimateState()) {
                        val cs = data.climateState
                        cluster.update {
                            it.copy(
                                outsideTemp = if (cs.hasOutsideTempCelsius()) cs.outsideTempCelsius else it.outsideTemp,
                                insideTemp = if (cs.hasInsideTempCelsius()) cs.insideTempCelsius else it.insideTemp,
                                driverTemp = if (cs.hasDriverTempSetting()) cs.driverTempSetting else it.driverTemp,
                                climateOn = if (cs.hasIsClimateOn()) cs.isClimateOn else it.climateOn,
                            )
                        }
                    }
                }
                delay(if (mcuReady) 2_500L else 60_000L)
            } else {
                // 行驶中这一道整个静默：只查本地状态、不发任何请求。
                delay(5_000L)
            }
        }
    }

    private fun applyCharge(data: Vehicle.VehicleData) {
        if (!data.hasChargeState()) return
        val charge = data.chargeState
        // SOC/充电功率只喂 cluster（驻车卡、指示条、零百起终点电量）；曲线版已删——
        // 行驶中不发 CHARGE，曲线只剩停车平线，统计页不再展示。
        cluster.update { snap ->
            var s = snap
            if (charge.hasBatteryLevel()) {
                s = s.copy(soc = charge.batteryLevel)
            }
            if (charge.hasChargerPower()) {
                s = s.copy(chargePowerKw = charge.chargerPower)
            }
            s.copy(
                charging = (charge.hasChargingState() &&
                    charge.chargingState.typeCase ==
                    Vehicle.ChargeState.ChargingState.TypeCase.CHARGING) ||
                    (charge.chargerPower ?: 0) > 0
            )
        }
        if (charge.hasBatteryLevel()) {
            val pending = pendingAccelSocResult
            if (pending != null) {
                accelResults.update { results ->
                    results.map { if (it === pending) it.copy(socEnd = charge.batteryLevel) else it }
                }
                pendingAccelSocResult = null
            }
        }
    }

    /**
     * 仪表天气（设置里默认关闭）。驻车版面显示在时钟下方，行车版面显示右上角迷你「时间+天气」。
     * 只在仪表页开着：缓存 12 小时内先显示，30 分钟一刷；失败 10 分钟后再试。
     * 行车中照常取数：全程走手机网络，不发 BLE、不占车机链路，与"行驶中只问车一件事"不冲突。
     */
    val weather = MutableStateFlow<com.teslalink.app.weather.WeatherNow?>(null)
    private var weatherJob: Job? = null

    fun startWeather() {
        if (weatherJob?.isActive == true) return
        weatherJob = viewModelScope.launch {
            if (c.prefs.getString("weather_on") != "1" || c.weather.apiKey() == null) {
                weather.value = null
                return@launch
            }
            weather.value = c.weather.cached()
            while (currentCoroutineContext().isActive) {
                val age = weather.value?.let { System.currentTimeMillis() - it.fetchedAtMs }
                if (age != null && age < WEATHER_REFRESH_MS) {
                    delay(WEATHER_REFRESH_MS - age)
                    continue
                }
                val got = c.weather.fetch()
                if (got != null) weather.value = got else delay(WEATHER_RETRY_MS)
            }
        }
    }

    fun stopWeather() {
        weatherJob?.cancel()
        weatherJob = null
        weather.value = null
    }

    @Volatile private var lastTripGpsMs = 0L
    private var tripGpsListener: android.location.LocationListener? = null

    /** 轨迹为什么没在记：必须能在仪表上看见，不能只写日志（他踩过的"静默失败"）。 */
    val tripNote = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** 手机罗盘方位角（0°=正北，顺时针度数，量化到 45°）。GPS 没给 bearing 时朝向格用它兜底。 */
    val phoneHeading = kotlinx.coroutines.flow.MutableStateFlow<Int?>(null)
    private var headingListener: android.hardware.SensorEventListener? = null

    /**
     * 旋转矢量传感器 → 方位角。量化到 45° 再发射：朝向格只显示八方位，不量化的话
     * 手机每一丝抖动都是一次 StateFlow 发射 + 整页重组。抖动总在 45° 边界附近时
     * 最多也就是两个格子来回，可接受。
     */
    fun startHeadingSensor() {
        if (headingListener != null) return
        val sm = c.appContext.getSystemService(android.content.Context.SENSOR_SERVICE) as
            android.hardware.SensorManager
        val sensor = sm.getDefaultSensor(android.hardware.Sensor.TYPE_ROTATION_VECTOR) ?: return
        val rot = FloatArray(9)
        val ori = FloatArray(3)
        val listener = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(e: android.hardware.SensorEvent) {
                android.hardware.SensorManager.getRotationMatrixFromVector(rot, e.values)
                android.hardware.SensorManager.getOrientation(rot, ori)
                val deg = Math.toDegrees(ori[0].toDouble())
                val bucket = ((((deg % 360) + 360) % 360) / 45).toInt() * 45
                if (phoneHeading.value != bucket) phoneHeading.value = bucket
            }

            override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}
        }
        runCatching {
            if (sm.registerListener(listener, sensor, android.hardware.SensorManager.SENSOR_DELAY_UI)) {
                headingListener = listener
            }
        }.onFailure {
            com.teslalink.app.debug.DebugLog.error(
                "trip", "罗盘注册失败：${it.javaClass.name}: ${it.message}",
            )
        }
    }

    fun stopHeadingSensor() {
        headingListener?.let { l ->
            runCatching {
                (c.appContext.getSystemService(android.content.Context.SENSOR_SERVICE) as
                    android.hardware.SensorManager).unregisterListener(l)
            }
        }
        headingListener = null
    }

    /**
     * 行程采样用手机 GPS（他要的：车速新鲜度第一，轨迹不许问车机）。
     * LOCATION 读占的是同一条 infotainmentMutex，一次失败重试（15s×3）能把车速冻住几十秒。
     * 手机 GPS 与车机同为 WGS-84，地图侧的坐标转换逻辑不用动。
     *
     * 权限按 provider 分开要：只给"大致位置"（COARSE）时 GPS 注册会抛 SecurityException，
     * 旧代码要求必须 FINE 就整段静默跳过——轨迹一条都不记，仪表那块永远是空圈。
     */
    private fun startTripGps() {
        if (tripGpsListener != null) return
        val ctx = c.appContext
        val fine = ctx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val coarse = ctx.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            tripNote.value = "没有定位权限，轨迹不记录"
            return
        }
        val lm = ctx.getSystemService(android.content.Context.LOCATION_SERVICE) as
            android.location.LocationManager
        val listener = android.location.LocationListener { loc ->
            // 双 provider 可能都回：8 秒内的重复点丢掉。
            if (loc.time - lastTripGpsMs < 8_000L) return@LocationListener
            if (loc.latitude == 0.0 && loc.longitude == 0.0) return@LocationListener
            // GPS 还没收敛/多路径反射时 accuracy 会飙到几十米开外，这种点画到图上就是漂移刺头。
            // 网络定位精度天生粗（它只是兜底），不按这个门限卡，否则只有粗权限时轨迹一条不剩。
            if (loc.provider == android.location.LocationManager.GPS_PROVIDER &&
                loc.hasAccuracy() && loc.accuracy > 50f
            ) {
                com.teslalink.app.debug.DebugLog.log(
                    "trip", "忽略低精度 GPS 点：accuracy=${"%.0f".format(loc.accuracy)}m",
                )
                return@LocationListener
            }
            lastTripGpsMs = loc.time
            tripNote.value = null
            c.trips.add(
                TripPoint(
                    System.currentTimeMillis(),
                    loc.latitude,
                    loc.longitude,
                    if (loc.hasBearing()) loc.bearing.toInt() else null,
                    cluster.value.speedKmh,
                ),
                // 里程计随点带上：本次里程走的是它的差值，GPS 点只负责形状和朝向。
                cluster.value.odometerHm,
            )
        }
        runCatching {
            var registered = false
            var enabledSomewhere = false
            for (provider in listOf(
                android.location.LocationManager.GPS_PROVIDER to fine,
                android.location.LocationManager.NETWORK_PROVIDER to true,
            )) {
                val (name, allowed) = provider
                if (!allowed) continue
                if (!lm.allProviders.contains(name)) continue
                if (!lm.isProviderEnabled(name)) {
                    enabledSomewhere = true
                    continue
                }
                runCatching {
                    lm.requestLocationUpdates(name, 10_000L, 5f, listener, android.os.Looper.getMainLooper())
                    registered = true
                }
            }
            if (registered) {
                tripGpsListener = listener
                tripNote.value = null
            } else {
                tripNote.value = if (enabledSomewhere) "系统定位未开启，轨迹不记录"
                else "只有大致位置权限，且网络定位不可用"
            }
        }.onFailure {
            tripNote.value = "GPS 注册失败：${it.javaClass.simpleName}"
        }
    }

    private fun stopTripGps() {
        tripGpsListener?.let { l ->
            runCatching {
                (c.appContext.getSystemService(android.content.Context.LOCATION_SERVICE) as
                    android.location.LocationManager).removeUpdates(l)
            }
        }
        tripGpsListener = null
    }

    /**
     * 轨迹车道：行驶中开手机 GPS（10s 粒度），停车 3 分钟收尾行程。不再向车机要任何数据。
     */
    private suspend fun locationLane() {
        while (currentCoroutineContext().isActive) {
            if (pollable() && cluster.value.speedKmh > 2f) {
                lastMovingAt = System.currentTimeMillis()
                startTripGps()
                delay(10_000L)
            } else {
                stopTripGps()
                if (lastMovingAt > 0 && c.trips.hasOpenTrip() &&
                    System.currentTimeMillis() - lastMovingAt > TripRecorder.PARK_TO_END_MS
                ) {
                    c.trips.finish(System.currentTimeMillis(), cluster.value.odometerHm)
                    lastMovingAt = 0L
                }
                delay(5_000L)
            }
        }
    }

    fun clear() {
        speedSeries.clear()
        powerSeries.clear()
        speed.value = emptyList()
        power.value = emptyList()
    }
}
