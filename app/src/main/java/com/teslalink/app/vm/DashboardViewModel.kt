package com.teslalink.app.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tesla.generated.carserver.vehicle.Vehicle
import com.teslalink.app.AppContainer
import com.teslalink.app.vehicle.Payloads
import com.teslalink.app.vehicle.VehicleConnection
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

    /** 超时/作废原因：必须就地显示在工具页（旧实现静默把按钮跳回"开始"，他不知道为什么）。 */
    val accelNote = MutableStateFlow<String?>(null)

    @Volatile private var lastAccelSpeed = 0f
    @Volatile private var lastAccelTick = 0L

    fun armAccel() {
        lastAccelSpeed = cluster.value.speedKmh
        lastAccelTick = System.currentTimeMillis()
        accelNote.value = null
        accelRun.value = AccelRun(armedAt = lastAccelTick)
    }

    fun cancelAccel() {
        accelRun.value = null
        accelNote.value = null
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
            when {
                t0 != null -> accelRun.value = run.copy(t0Ms = t0, socStart = cluster.value.soc)
                now - run.armedAt > 120_000 -> {
                    accelRun.value = null
                    accelNote.value = "已自动作废：待命 120 秒没有起步（车速没过 0.5 km/h）。再点一次「开始零百测试」重新待命。"
                }
            }
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
                socEnd = cluster.value.soc,
            )
            accelResults.value = (listOf(result) + accelResults.value).take(20)
            accelRun.value = null
            // 行驶中慢道已经彻底静默，cluster.soc 还是出发前的值——终点电量要在这条跑完的
            // 此刻单独补一次 quick 读（一次性，测试已经结束），否则"耗电"永远是 0。
            viewModelScope.launch {
                val data = readVehicleData(listOf(Payloads.Category.CHARGE), quick = true)
                if (data == null) return@launch
                applyCharge(data)
                val end = cluster.value.soc
                accelResults.update { list ->
                    if (list.isEmpty() || list.first() !== result) list
                    else list.mapIndexed { i, r -> if (i == 0) r.copy(socEnd = end) else r }
                }
            }
            return
        }
        if (now - t0 > 60_000) {
            accelRun.value = null
            accelNote.value = "已自动作废：起步后 60 秒没有跑到 100 km/h（车速流中断或场地不够长）。"
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

    private fun append(buffer: MutableStateFlow<List<Point>>, point: Point) {
        // 本次启动内全量保留（他 2026-10-13 要求）；渲染侧在 LineChart 里抽稀，数据不丢。
        buffer.update { it + point }
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
                }
            }
        }
    }

    @Volatile private var vehicleReadInFlight = false

    /**
     * One vehicle-data read at a time. An infotainment attempt can take tens of seconds
     * (handshake + wake + retry); without this guard the 250 ms lane would pile an unbounded
     * queue onto that mutex and the app looks hung while "仪表" shows 等待数据.
     *
     * quick=true 给车速道用：单次尝试、2.5s 超时。infotainmentMutex 串行一切车机域对话，
     * 慢路径的 15s×3 重试一旦排在它前面，车速就得等十几秒——快速读失败下一轮 250ms 再来。
     */
    private suspend fun readVehicleData(
        categories: List<Payloads.Category>,
        quick: Boolean = false,
        attempts: Int? = null,
        wake: Boolean = true,
    ): Vehicle.VehicleData? {
        if (vehicleReadInFlight) return null
        vehicleReadInFlight = true
        return try {
            if (quick) {
                c.connection.vehicleData(
                    categories,
                    attempts = attempts ?: 1,
                    timeoutMs = 2_500,
                    wake = wake,
                )
            } else {
                c.connection.vehicleData(
                    categories,
                    attempts = attempts ?: 3,
                    wake = wake,
                )
            }
        } catch (e: Exception) {
            null
        } finally {
            vehicleReadInFlight = false
        }
    }

    /**
     * Ref-counted: 仪表 and 统计 share this VM, and on a tab switch the outgoing onDispose can
     * run after the incoming effect — without counting, the switch would silently kill polling.
     */
    private var pollingRefs = 0

    fun startBlePolling() {
        if (++pollingRefs > 1) return
        // 车道自己抛异常时整条重启，而不是让协程把进程带走（见 laneTick）。
        fastJob = viewModelScope.launch { while (currentCoroutineContext().isActive) laneTick("车速", ::fastLane) }
        slowJob = viewModelScope.launch { while (currentCoroutineContext().isActive) laneTick("驻车读数", ::slowLane) }
        locationJob = viewModelScope.launch { while (currentCoroutineContext().isActive) laneTick("轨迹", ::locationLane) }
        // 朝向格的兜底罗盘跟页面走：仪表/统计可见时才采样，离开即注销。
        startHeadingSensor()
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
     * Fast lane: DRIVE-only. Target cadence is measured from tick start, and follows the real
     * round trip while moving（下限 250 ms）：一次 250 ms 的读排在别人的响应后面会直接翻倍。
     */
    private suspend fun fastLane() {
        while (currentCoroutineContext().isActive) {
            val tickStarted = System.currentTimeMillis()
            val interval = when {
                // Head unit not reachable yet: probe slowly instead of flooding a broken link.
                !c.connection.mcuSessionReady.value -> 5_000L
                cluster.value.speedKmh > 0.5f -> (lastFastRtt + 60L).coerceIn(250L, 500L)
                else -> 600L
            }
            if (pollable()) {
                val started = System.currentTimeMillis()
                // 车速道禁 wake（wake=false）：车机睡着时"唤醒+30s 重试"会持锁冻死 250ms 节奏，
                // 宁可这轮失败下一轮再来。行驶信号要带新鲜度（见下），车机睡着会自动熄灭。
                val data = readVehicleData(listOf(Payloads.Category.DRIVE), quick = true, wake = false)
                if (data != null) lastFastRtt = System.currentTimeMillis() - started
                // 行驶信号唯一直接来源：其他页（VCSEC 轮询、轨迹）据此决定行驶中停不停。
                // **必须看数据新鲜度**：车机睡着/读持续失败时 cluster 速度冻在最后值，
                // 不加门限会让"行驶中"永久卡死——控制页驻车闸门全禁用、慢车道永不恢复。
                // 阈值 5s > 行驶中正常读间隔（≤2.5s），不会在正常行驶时闪烁。
                val fresh = System.currentTimeMillis() - cluster.value.updatedAtMs < 5_000L
                c.vehicleMoving.value = fresh && cluster.value.speedKmh > 2f
                // 行驶闩（D/R 息屏/后台继续监听的依据）也只认新鲜数据：
                // D/R 或在动 → 续 90s；明确读到 P/N → 立即清零。读失败不动它，靠 90s 过期兜底。
                if (data != null) {
                    val shift = cluster.value.shift
                    if (cluster.value.speedKmh > 2f || shift == "D" || shift == "R") {
                        c.drivingUntilMs.value = System.currentTimeMillis() + 90_000L
                    } else if (shift == "P" || shift == "N") {
                        c.drivingUntilMs.value = 0L
                    }
                }
                if (accelRun.value != null) evaluateAccel(System.currentTimeMillis(), cluster.value.speedKmh)
                if (data != null) {
                    val now = System.currentTimeMillis()
                    if (data.hasDriveState()) {
                        val drive = data.driveState
                        cluster.update { snap ->
                            var s = snap
                            // speedFloat 是精确值；speed 是整数 mph（向下取整）。两个字段可能同时在，
                            // 必须优先用 float——"先 floor 再 ceil" 会丢掉最多 1.6 km/h，正好差 1。
                            if (drive.hasSpeedFloat()) {
                                val kmh = drive.speedFloat * 1.609344f
                                s = s.copy(speedKmh = kmh)
                                append(speed, Point(now, kmh))
                            } else if (drive.hasSpeed()) {
                                val kmh = drive.speed * 1.609344f
                                s = s.copy(speedKmh = kmh)
                                append(speed, Point(now, kmh))
                            }
                            if (drive.hasPower()) {
                                s = s.copy(powerKw = drive.power)
                                append(power, Point(now, drive.power.toFloat()))
                            }
                            if (drive.hasShiftState()) {
                                s = s.copy(
                                    shift = when (drive.shiftState.typeCase) {
                                        Vehicle.ShiftState.TypeCase.P -> "P"
                                        Vehicle.ShiftState.TypeCase.R -> "R"
                                        Vehicle.ShiftState.TypeCase.N -> "N"
                                        Vehicle.ShiftState.TypeCase.D -> "D"
                                        else -> s.shift
                                    }
                                )
                            }
                            if (drive.hasOdometerInHundredthsOfAMile()) {
                                val hm = drive.odometerInHundredthsOfAMile
                                s = s.copy(
                                    odometerKm = hm * 0.0160934f,
                                    odometerHm = hm,
                                )
                            }
                            s.copy(updatedAtMs = now)
                        }
                    }
                    // 本次最高车速按 250ms 全采样记，不按 10s 的 GPS 点算（后者会明显偏低）。
                    c.trips.onSpeed(cluster.value.speedKmh)
                }
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
            if (pollable() && cluster.value.speedKmh <= 0.5f) {
                val mcuReady = c.connection.mcuSessionReady.value
                // 探测模式（车机不在=车睡着）：60s 一次、单次尝试——唤醒预算压到每分钟至多 1 次。
                // 旧实现 10s 节奏 × attempts=3（每次失败都会 wake+30s 重试握手），GATT 挂着而
                // 车机睡着时最多 6 次唤醒/3 分钟循环不止，正撞"别反复唤醒车机"。
                readVehicleData(listOf(Payloads.Category.CHARGE), attempts = if (mcuReady) 3 else 1)
                    ?.let { applyCharge(it) }
                readVehicleData(listOf(Payloads.Category.CLIMATE), attempts = if (mcuReady) 3 else 1)?.let { data ->
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
    }

    fun stopBlePolling() {
        if (pollingRefs > 0) pollingRefs--
        if (pollingRefs > 0) return
        fastJob?.cancel()
        slowJob?.cancel()
        locationJob?.cancel()
        fastJob = null
        slowJob = null
        locationJob = null
        stopTripGps()
        stopHeadingSensor()
        // 离开仪表/统计后没人再维护行驶信号：回到"不知道"，让各页恢复正常轮询。
        c.vehicleMoving.value = false
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
        speed.value = emptyList()
        power.value = emptyList()
    }
}
