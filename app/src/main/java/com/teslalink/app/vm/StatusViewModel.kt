package com.teslalink.app.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tesla.generated.carserver.vehicle.Vehicle
import com.tesla.generated.vcsec.Vcsec
import com.teslalink.app.AppContainer
import com.teslalink.app.tools.AccidentHelper
import com.teslalink.app.tools.PlaceResult
import com.teslalink.app.vehicle.Payloads
import com.teslalink.app.vehicle.VehicleConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class StatusSnapshot(
    val batteryLevel: Int? = null,
    val batteryRangeKm: Float? = null,
    val chargeLimit: Int? = null,
    val chargingState: String? = null,
    val chargePowerKw: Int? = null,
    val insideTemp: Float? = null,
    val outsideTemp: Float? = null,
    val climateOn: Boolean? = null,
    val driverTemp: Float? = null,
    /** true = AUTO；false = 手动风量（协议里的 hvac_auto_request = Override）。 */
    val hvacAuto: Boolean? = null,
    /** 当前风量档，车机给的是原始数值；BLE 协议里没有设风量的指令，只能读。 */
    val fanLevel: Int? = null,
    val speedKmh: Float? = null,
    val powerKw: Int? = null,
    val latitude: Float? = null,
    val longitude: Float? = null,
    val heading: Int? = null,
    val locked: Boolean? = null,
    val sleepStatus: String? = null,
    val userPresence: Boolean? = null,
    val rearTrunkOpen: Boolean? = null,
    val frunkOpen: Boolean? = null,
    val chargePortOpen: Boolean? = null,
    val shiftState: String? = null,
    // 空调细项：车没装某个设备时字段不会出现（保持 null → UI 隐藏对应控件）。
    val seatHeatLeft: Int? = null,
    val seatHeatRight: Int? = null,
    val seatCoolLeft: Int? = null,
    val seatCoolRight: Int? = null,
    val swHeater: Boolean? = null,
    val maxDefrost: Boolean? = null,
    val keeper: String? = null,
    val bioweaponOn: Boolean? = null,
    val sentryOn: Boolean? = null,
    val updatedAtMs: Long = 0,
    /** When the head unit last answered; 0 means its fields are carried over and stale. */
    val mcuUpdatedAtMs: Long = 0,
    /** Only an actual speed/shift field can renew its clock, not CHARGE/CLIMATE or an empty DRIVE. */
    val driveSpeedAtMs: Long? = null,
    val driveShiftAtMs: Long? = null,
    /** 哨兵字段自己的时钟：CLOSURES 不是每轮都轮到，没有它旧值会被别的类"续命"成假新鲜。 */
    val sentryAtMs: Long? = null,

    // ---- 以下全是"车机已经在答、我们之前没解析"的字段（2026-11-01 挖出来的 A 类）--------
    // 统一原则：字段级 null = 这一轮没给，合并时保留旧值；UI 遇到 null 就隐藏该行，不放占位符。

    /** 额定/理想/标准三种续航（车机同时给三个口径，国内常用 ideal 显示在车机屏上）。km */
    val rangeRatedKm: Float? = null,
    val rangeIdealKm: Float? = null,
    val rangeEstKm: Float? = null,
    /** 充满 / 充到当前限值的剩余分钟数。 */
    val minutesToFullCharge: Int? = null,
    val minutesToChargeLimit: Int? = null,
    /** 桩实际给了多少：电压 V、实际电流 A、引导（pilot）电流 A、相数。家充排障就看这三个是否自洽。 */
    val chargerVoltage: Float? = null,
    val chargerActualAmps: Int? = null,
    val chargerPilotAmps: Int? = null,
    val chargerPhases: Int? = null,
    /** 本次充电已充进的电量 kWh 与增加的额定里程 km。 */
    val energyAddedKwh: Float? = null,
    val addedRangeKm: Float? = null,
    /** 谷电结束时间（车机报的是 epoch 秒，可能为 0=没设）。 */
    val offPeakEndsAtSec: Long? = null,
    /** 车端定时充电/出发：模式 Off/StartAt/DepartBy，两个"当日分钟数"，以及出发前是否预设座舱。 */
    val scheduledChargingMode: String? = null,
    val scheduledChargeAtMinute: Int? = null,
    val scheduledDepartAtMinute: Int? = null,
    val preconditionOnDeparture: Boolean? = null,
    /** 电池预热（超级充电前）与"想预热但没电"。 */
    val batteryHeater: Boolean? = null,
    val batteryHeaterNoPower: Boolean? = null,
    val chargePortColdWeather: Boolean? = null,
    /** 充电线是否已解扣（锁车后能不能拔枪）。 */
    val cableUnlatched: Boolean? = null,

    /** 后排/三排座椅加热档位（车没装就不出现）。 */
    val seatHeatRearLeft: Int? = null,
    val seatHeatRearRight: Int? = null,
    val seatHeatRearCenter: Int? = null,
    val seatHeatThirdLeft: Int? = null,
    val seatHeatThirdRight: Int? = null,
    val mirrorHeater: Boolean? = null,
    val wiperBladeHeater: Boolean? = null,
    /** 座舱预设中（车机在按时刻表提前升温/降温）。之前被错当成 MAX 除霜用了。 */
    val preconditioningActive: Boolean? = null,
    val autoSeatClimateLeft: Boolean? = null,
    val autoSeatClimateRight: Boolean? = null,
    val autoSteeringHeat: Boolean? = null,
    val steeringHeatLevel: Int? = null,
    /** 除霜真实字段：前/后除霜器开关（MAX 除霜是动作，回读只有这两个）。 */
    val frontDefrosterOn: Boolean? = null,
    val rearDefrosterOn: Boolean? = null,
    /** 离车高温保护（COP）：允许 / 车机报的状态名 / 正在主动制冷。 */
    val copAllowed: Boolean? = null,
    val copState: String? = null,
    val copCooling: Boolean? = null,

    /** V2L 对外放电。国内车型多半整族不报——报了才显示，没报就一行都不留。 */
    val powershareAllowed: Boolean? = null,
    val powershareEnabled: Boolean? = null,
    val powershareStatus: String? = null,
    val powershareLoadKw: Float? = null,
    val outletSocLimit: Int? = null,

    /** 天窗开度百分比（没装天窗时字段不出现）。 */
    val sunRoofPercent: Int? = null,
    /** 四门四窗逐位状态。 */
    val doorDriverFront: Boolean? = null,
    val doorDriverRear: Boolean? = null,
    val doorPassengerFront: Boolean? = null,
    val doorPassengerRear: Boolean? = null,
    val windowDriverFront: Boolean? = null,
    val windowDriverRear: Boolean? = null,
    val windowPassengerFront: Boolean? = null,
    val windowPassengerRear: Boolean? = null,
    /** 远程启动过（还没踩刹车熄火前为真）与代客模式。 */
    val remoteStart: Boolean? = null,
    val valetMode: Boolean? = null,

    /** 车机里存的家 / 公司坐标（GCJ-02 口径同 location_state），通勤场景的免费基准点。 */
    val homeLat: Float? = null,
    val homeLon: Float? = null,
    val workLat: Float? = null,
    val workLon: Float? = null,
) {
    fun shiftIsMoving(): Boolean = shiftState == "R" || shiftState == "N" || shiftState == "D"

    /** 哨兵读数的新鲜窗口：它只在进页/命令后/展开时被读，60s 内算可用，过期只能算"较旧"。 */
    fun sentryIsFresh(now: Long = System.currentTimeMillis()): Boolean =
        readingIsFresh(sentryAtMs, now, 60_000L)

    /**
     * 控制按钮仍把 N 视为非驻车；读数策略允许低速 N，但都只认 DRIVE 自己的时间戳。
     */
    fun recentlyMoving(now: Long = System.currentTimeMillis()): Boolean =
        (readingIsFresh(driveSpeedAtMs, now, 60_000L) && kotlin.math.abs(speedKmh ?: 0f) > 2f) ||
            (readingIsFresh(driveShiftAtMs, now, 60_000L) && shiftIsMoving())

    // 行驶中 VCSEC 对开闭件一律报 UNKNOWN，而行驶中物理上不可能开着 —— 显示层推断为关。
    fun rearTrunkShown(moving: Boolean): Boolean? = rearTrunkOpen ?: if (moving) false else null

    fun frunkShown(moving: Boolean): Boolean? = frunkOpen ?: if (moving) false else null

    fun chargePortShown(moving: Boolean): Boolean? = chargePortOpen ?: if (moving) false else null
}

internal fun StatusSnapshot.driveReadings(): DriveReadings =
    DriveReadings(speedKmh, shiftState, driveSpeedAtMs, driveShiftAtMs)

internal fun applyStatusDrive(
    previous: StatusSnapshot,
    data: Vehicle.VehicleData,
    now: Long,
): StatusSnapshot {
    val sample = parseDriveSample(data)
    val readings = previous.driveReadings().updated(sample, now)
    return previous.copy(
        speedKmh = readings.speedKmh,
        shiftState = readings.shift,
        driveSpeedAtMs = readings.speedAtMs,
        driveShiftAtMs = readings.shiftAtMs,
        powerKw = if (data.hasDriveState() && data.driveState.hasPower()) data.driveState.power else previous.powerKw,
    )
}

internal fun statusReadsBlocked(
    vehicleMoving: Boolean,
    drivingUntilMs: Long,
    snapshot: StatusSnapshot?,
    now: Long,
): Boolean = vehicleMoving || drivingUntilMs > now || snapshot?.driveReadings()?.moving(now, 60_000L) == true

internal fun statusCategoryAllowed(
    category: Payloads.Category,
    vehicleMoving: Boolean,
    drivingUntilMs: Long,
    snapshot: StatusSnapshot?,
    now: Long,
): Boolean = !statusReadsBlocked(vehicleMoving, drivingUntilMs, snapshot, now) &&
    (category == Payloads.Category.DRIVE || snapshot?.driveReadings()?.allowsSlowRead(now, drivingUntilMs) == true)

/**
 * 唤醒探针的唯一准入：人主动进页面/命令后回读、车机域没有活会话、且此刻没在行驶。
 * 车机睡着时所有车机域读都在 2.5s 内失败，驻车想看电量只能唤醒一次；但唤醒握手的几十秒里
 * infotainmentMutex 是被占着的，所以一轮只发一次、只发 CHARGE 一类。
 */
internal fun wakeProbeAllowed(
    force: Boolean,
    mcuSessionReady: Boolean,
    readsBlocked: Boolean,
    linkConnected: Boolean,
): Boolean = force && !mcuSessionReady && !readsBlocked && linkConnected

/**
 * 唤醒探针每一步的准入：车速道正在拿帧、车开始动、或超过总时限，任一成立就收手。
 * 唤醒本身已经发出去了，所以"收手"不会让车机继续睡——它只是不再占链路等回执。
 */
internal fun wakeProbeStepAllowed(
    telemetryLaneActive: Boolean,
    readsBlocked: Boolean,
    nowMs: Long,
    deadlineMs: Long,
): Boolean = !telemetryLaneActive && !readsBlocked && nowMs < deadlineMs

/** 车机从收到唤醒到能答话一般十几到几十秒：给探针一个总时限，超了就认定这次没起来。 */
private const val WAKE_PROBE_WAIT_MS = 30_000L
private const val WAKE_PROBE_RETRY_MS = 2_000L

/** BLE-only status: merges body-controller state with infotainment vehicle data. */
class StatusViewModel(private val c: AppContainer) : ViewModel() {

    val snapshot = MutableStateFlow<StatusSnapshot?>(null)
    val error = MutableStateFlow<String?>(null)

    /** Not a failure: the head unit is asleep, so the MCU fields are deliberately not read. */
    val hint = MutableStateFlow<String?>(null)
    val refreshing = MutableStateFlow(false)

    /** 车辆详情的"位置翻译"：按需（展开分区时）才发网络请求，不在轮询里自动打。 */
    val place = MutableStateFlow<PlaceResult?>(null)

    @Volatile private var placeInFlight = false
    private var placeKey: String? = null

    private var bodyJob: Job? = null

    /** One read of each kind at a time: a slow round trip must not queue up behind itself. */
    @Volatile private var mcuInFlight = false
    @Volatile private var bodyInFlight = false

    /** Why the head unit didn't answer this round; surfaced so "--" is never silent. */
    @Volatile private var lastMcuError: String? = null
    private val readLog = TelemetryReadLog("status-read")

    private fun readsBlocked(): Boolean = statusReadsBlocked(
        c.vehicleMoving.value, c.drivingUntilMs.value, snapshot.value, System.currentTimeMillis(),
    )

    /** With no session, allow one bounded no-wake probe, then wait for a session/manual refresh. */
    @Volatile private var autoMcuProbes = 0

    init {
        // Once the link is gone we know nothing: leaving the last SOC/lock on screen would be
        // read as current by both the 状态 cards and the 控制 pills/gates.
        viewModelScope.launch {
            c.connection.state.collect { state ->
                if (state == VehicleConnection.State.DISCONNECTED) {
                    snapshot.value = null
                    hint.value = null
                    place.value = null
                    placeKey = null
                    autoMcuProbes = 0
                }
            }
        }
    }

    /**
     * 把车辆坐标翻成地址。触发点是"读到新坐标"或"展开车辆详情"，同一个位置只问一次：
     * 高德要 Key、OSM 国内常连不上，而且这是网络请求，不该混进 3 秒一轮的蓝牙轮询里。
     */
    fun resolvePlace(force: Boolean = false) {
        val s = snapshot.value
        val lat = s?.latitude
        val lng = s?.longitude
        if (lat == null || lng == null) {
            place.value = null
            placeKey = null
            return
        }
        val key = "%.4f,%.4f".format(lat, lng)
        // 同一个位置（约 11 m 网格）只问一次，失败也算问过——否则每次进控制页都白打一趟网络。
        // 想重试就点「重新解析地址」（force）。
        if (!force && key == placeKey) return
        if (placeInFlight) return
        placeInFlight = true
        viewModelScope.launch {
            try {
                val amapKey = c.prefs.getString("amap_key")?.ifBlank { null }
                val result = withContext(Dispatchers.IO) {
                    AccidentHelper.placeFromWgs(lat.toDouble(), lng.toDouble(), amapKey)
                }
                placeKey = key
                place.value = result
            } finally {
                placeInFlight = false
            }
        }
    }

    /**
     * Ref-counted like the dashboard lanes: 控制 owns the body poller and 状态 tears down on
     * dispose, and Compose may run the incoming effect before the outgoing one — without counting,
     * switching 控制→状态→控制 would leave the lock/closure pills frozen for the rest of the visit.
     */
    private var bodyRefs = 0

    /**
     * Cheap VCSEC-only polling: lock state, closures and sleep status. These requests are
     * unauthenticated and go straight to the security controller, so they neither wake the
     * head unit nor depend on the infotainment session — this is what drives the button
     * states on the control page.
     */
    fun startBodyPolling(intervalMs: Long = 3_000) {
        if (++bodyRefs > 1) return
        bodyJob = viewModelScope.launch {
            while (isActive) {
                // 行驶中不需要 GET_STATUS：门锁/开闭件在行车期间不会变（显示层已按"行驶中推断为关"），
                // 省下链路给车速：D/R 零速也由共享 90s 行驶闩拦截，不只看 vehicleMoving。
                val moving = readsBlocked()
                if (!moving) refreshBody()
                delay(if (moving) 10_000L else intervalMs)
            }
        }
    }

    fun stopBodyPolling() {
        if (bodyRefs > 0) bodyRefs--
        if (bodyRefs > 0) return
        bodyJob?.cancel()
        bodyJob = null
    }

    /**
     * @param force bypasses the "link reserved by a command/self-test" stand-down — used for the
     * read-back right after a command, which is exactly when the buttons need fresh state.
     */
    fun refreshBody(force: Boolean = false) {
        if (bodyInFlight || readsBlocked()) return
        if (!force && c.connection.exclusive.value) return
        if (c.connection.state.value != VehicleConnection.State.CONNECTED) return
        viewModelScope.launch {
            val body = readBody() ?: return@launch
            snapshot.update { (it ?: StatusSnapshot()).withBody(body) }
        }
    }

    /** Also used at the end of a multi-category refresh: never append VCSEC once driving starts. */
    private suspend fun readBody(): VehicleConnection.BodyControllerState? {
        if (bodyInFlight || readsBlocked() || c.connection.state.value != VehicleConnection.State.CONNECTED) return null
        bodyInFlight = true
        val started = System.currentTimeMillis()
        return try {
            c.connection.bodyControllerState().also { readLog.record("VCSEC", started) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            readLog.record("VCSEC", started, "${e.javaClass.simpleName}: ${e.message}")
            null
        } finally {
            bodyInFlight = false
        }
    }

    private fun StatusSnapshot.withBody(body: VehicleConnection.BodyControllerState): StatusSnapshot =
        copy(
            locked = when (body.lockState) {
                0 -> false
                1 -> true
                else -> null
            },
            sleepStatus = when (body.sleepStatus) {
                1 -> "醒着"; 2 -> "休眠"; else -> null
            },
            userPresence = body.userPresence?.takeIf { it != 0 }?.let { it == 2 },
            rearTrunkOpen = closureOpen(body.closures?.rearTrunk),
            frunkOpen = closureOpen(body.closures?.frontTrunk),
            // On Model 3/Y the security controller reports the charge port as UNKNOWN, so keep
            // whatever the head unit last said instead of blanking it to 未知.
            chargePortOpen = closureOpen(body.closures?.chargePort) ?: chargePortOpen,
            updatedAtMs = System.currentTimeMillis(),
        )

    private fun closureOpen(state: Vcsec.ClosureState_E?): Boolean? = when (state) {
        null -> null
        Vcsec.ClosureState_E.CLOSURESTATE_CLOSED -> false
        Vcsec.ClosureState_E.CLOSURESTATE_OPEN,
        Vcsec.ClosureState_E.CLOSURESTATE_AJAR,
        Vcsec.ClosureState_E.CLOSURESTATE_OPENING,
        Vcsec.ClosureState_E.CLOSURESTATE_CLOSING,
        -> true
        else -> null
    }

    fun refresh(force: Boolean = false) {
        if (mcuInFlight) return
        if (!force && c.connection.exclusive.value) return
        // force 仅绕过命令占用，不绕过行驶闩：D/R 等灯、90s 闩有效期间也不给大类读数排队。
        if (readsBlocked()) {
            hint.value = "行驶中：暂停读取车机大类（带宽让给车速），停车后自动恢复"
            return
        }
        viewModelScope.launch {
            if (mcuInFlight || readsBlocked()) return@launch
            mcuInFlight = true
            refreshing.value = true
            error.value = null
            try {
                if (c.connection.state.value != VehicleConnection.State.CONNECTED) {
                    error.value = "未连接：状态读取需要手机在车旁并完成 BLE 连接"
                    return@launch
                }
                val mcuReady = c.connection.mcuSessionReady.value
                val wakeProbe = wakeProbeAllowed(
                    force = force,
                    mcuSessionReady = mcuReady,
                    readsBlocked = readsBlocked(),
                    linkConnected = c.connection.state.value == VehicleConnection.State.CONNECTED,
                )
                if (force || mcuReady || autoMcuProbes < 1) {
                    if (!mcuReady) autoMcuProbes++
                    hint.value = null
                    val fresh = bleSnapshot(wakeProbe = wakeProbe)
                    // The head unit answered: hand the auto poller its probe budget back.
                    if (fresh.mcuUpdatedAtMs > 0) autoMcuProbes = 0
                    // Atomic merge: this round trip takes seconds, during which the 3s body poll may
                    // have written lock/closure values. Assigning the whole snapshot would roll them back.
                    snapshot.update { prev -> mergeSnapshot(prev, fresh) }
                } else {
                    refreshBody(force = true)
                    // Keep the reason visible instead of letting the next 10 s round erase it.
                    val why = lastMcuError?.let { "（上次尝试：$it）" }.orEmpty()
                    hint.value = "车机未唤醒：电量/温度/位置需要车机响应$why，退出再进「控制」页会自动再唤醒一次"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error.value = e.message
            } finally {
                refreshing.value = false
                mcuInFlight = false
            }
        }
    }

    /**
     * 命令后的针对性回读：整轮 bleSnapshot 的第一类是 DRIVE，DRIVE 不给完整速度/档位就 break，
     * CLOSURES（哨兵这类 MCU 侧状态）会被连坐跳过；而且车机对命令的状态迁移是异步的，
     * ack 后立刻读常拿到旧值。这里延后 2 秒、只读 CLOSURES 一类（单次尝试的原子短读）。
     */
    fun refreshClosuresDelayed(delayMs: Long = 2_000) {
        viewModelScope.launch {
            delay(delayMs)
            if (mcuInFlight || readsBlocked() ||
                c.connection.state.value != VehicleConnection.State.CONNECTED ||
                !c.connection.mcuSessionReady.value
            ) {
                return@launch
            }
            val started = System.currentTimeMillis()
            val data = try {
                c.connection.vehicleData(
                    listOf(Payloads.Category.CLOSURES), attempts = 1, timeoutMs = 1_200, wake = false,
                ).also { readLog.record("CLOSURES", started) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                readLog.record("CLOSURES", started, "${e.javaClass.simpleName}: ${e.message}")
                return@launch
            }
            snapshot.update { prev -> mergeSnapshot(prev, parseBleVehicleData(data)) }
        }
    }

    /**
     * Merge a fresh read into the existing snapshot: fields the head unit didn't answer this time
     * keep their previous value (timestamped when they were last live), and body-controller fields
     * are never reverted by an older MCU read.
     */
    private fun mergeSnapshot(prev: StatusSnapshot?, fresh: StatusSnapshot): StatusSnapshot {
        if (prev == null) return fresh
        // 每一类都是独立请求：本轮没答上的那一类值是 null，不能因此把上一轮读到的打成未知。
        // "车机这轮到底有没有答"由 mcuUpdatedAtMs + 界面的新鲜度窗口负责，不在这里靠清空来表达。
        return fresh.mergedInto(prev).copy(
            locked = fresh.locked ?: prev.locked,
            sleepStatus = fresh.sleepStatus ?: prev.sleepStatus,
            userPresence = fresh.userPresence ?: prev.userPresence,
            rearTrunkOpen = fresh.rearTrunkOpen ?: prev.rearTrunkOpen,
            frunkOpen = fresh.frunkOpen ?: prev.frunkOpen,
            chargePortOpen = fresh.chargePortOpen ?: prev.chargePortOpen,
            updatedAtMs = maxOf(fresh.updatedAtMs, prev.updatedAtMs),
        )
    }

    /**
     * 读到一类就立刻发布，不等整轮跑完（一轮要好几秒，界面把它当"状态来得特别慢"）。
     * mergedInto 会沿用现有快照里本轮还没读到的字段，避免先答上的类别把后答的打成未知；
     * VCSEC 那几个字段不在表里，由 3 秒身体轮询维护，必须原样从现有快照拿回来。
     */
    private fun publishProgress(partial: StatusSnapshot) {
        snapshot.update { prev ->
            val base = prev ?: return@update partial
            partial.mergedInto(base).copy(
                locked = base.locked,
                sleepStatus = base.sleepStatus,
                userPresence = base.userPresence,
                rearTrunkOpen = base.rearTrunkOpen,
                frunkOpen = base.frunkOpen,
                chargePortOpen = partial.chargePortOpen ?: base.chargePortOpen,
                updatedAtMs = maxOf(partial.updatedAtMs, base.updatedAtMs),
            )
        }
    }

    /**
     * 唤醒探针：车机睡着时所有车机域读都会失败，驻车想看电量只能先唤醒。
     * 唤醒指令本身走 VCSEC（不占车机域锁），之后**只用单次尝试的原子短读等车机起来**：
     * 每次一类 CHARGE、2s 总预算、不发第二次唤醒；每一步前都重新问"车速道是不是在拿帧/车是不是
     * 在动"，是就立刻收手。所以车机域锁最多被一次 2 秒的读占住，而不是一条几十秒的长请求。
     */
    private suspend fun wakeProbeCharge(): Vehicle.VehicleData? {
        val wake = c.connection.wakeCommand()
        if (!wake.ok) {
            lastMcuError = wake.message
            hint.value = "唤醒车机没成功（${wake.message ?: "无应答"}）：人在车旁时再切进本页会自动重试"
            return null
        }
        val deadline = System.currentTimeMillis() + WAKE_PROBE_WAIT_MS
        var silentRounds = 0
        while (wakeProbeStepAllowed(c.connection.telemetryLaneActive(), readsBlocked(), System.currentTimeMillis(), deadline)) {
            val started = System.currentTimeMillis()
            val data = try {
                c.connection.vehicleData(
                    listOf(Payloads.Category.CHARGE), attempts = 1, timeoutMs = 2_000, wake = false,
                ).also { readLog.record("唤醒", started) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                silentRounds++
                lastMcuError = e.message
                readLog.record("唤醒", started, "${e.javaClass.simpleName}: ${e.message}")
                null
            }
            if (data != null) return data
            delay(WAKE_PROBE_RETRY_MS)
        }
        if (c.connection.telemetryLaneActive()) {
            hint.value = "车速道要用链路，已停止唤醒等待：停车后再进本页会接着唤醒"
        } else if (readsBlocked()) {
            hint.value = "已发唤醒但车开始动了，停止等待：唤醒后车机会自己醒着，稍后再进本页读数"
        } else if (silentRounds > 0) {
            hint.value = "已发唤醒，车机 ${WAKE_PROBE_WAIT_MS / 1000} 秒内没答话（${lastMcuError ?: "无应答"}）：再进本页会重试"
        }
        return null
    }

    private suspend fun bleSnapshot(wakeProbe: Boolean = false): StatusSnapshot {
        // One category per request: a multi-category response exceeds the negotiated MTU and the
        // vehicle rejects it wholesale (RESPONSE_MTU_EXCEEDED). A failed category just stays null.
        // DRIVE first: a P → D transition at zero speed must stop the remaining categories,
        // even when the dashboard is not visible. Missing DRIVE does not grant parking evidence.
        var snap = StatusSnapshot()
        lastMcuError = null
        var mcuFailures = 0
        if (wakeProbe) {
            val probed = wakeProbeCharge()
            if (probed != null) {
                snap = parseBleVehicleData(probed).mergedInto(snap)
                publishProgress(snap)
            }
        }
        for (category in listOf(
            Payloads.Category.DRIVE,
            Payloads.Category.CLOSURES,
            Payloads.Category.CLIMATE,
            Payloads.Category.CHARGE,
            Payloads.Category.LOCATION,
        )) {
            // Re-evaluate before EVERY request, not just at the start of the refresh.
            if (!statusCategoryAllowed(
                    category, c.vehicleMoving.value, c.drivingUntilMs.value,
                    snapshot.value, System.currentTimeMillis(),
                )
            ) {
                hint.value = if (readsBlocked()) "行驶中：暂停读取车机大类（带宽让给车速）"
                else "等待新鲜的 P/N 档及低速读数，暂不读取车机慢类"
                break
            }
            val started = System.currentTimeMillis()
            val data = try {
                c.connection.vehicleData(
                    // 单类、单次尝试、1.2s：本页没有车速道在跑（车速轮询只属于仪表/统计/工具），
                    // 所以预算可以比仪表慢道的 700ms 宽松一点，但仍然是一口一停的原子读。
                    listOf(category), attempts = 1, timeoutMs = 1_200, wake = false,
                ).also { readLog.record(category.name, started) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mcuFailures++
                if (lastMcuError == null) lastMcuError = e.message
                readLog.record(category.name, started, "${e.javaClass.simpleName}: ${e.message}")
                // Don't repeat a failed session probe for every remaining category.
                if (!c.connection.mcuSessionReady.value || category == Payloads.Category.DRIVE) break
                null
            } ?: continue
            snap = parseBleVehicleData(data).mergedInto(snap)
            // Publish actual fields before updating the shared latch; never derive it from snap's
            // carried-over speed/shift. A new D/R frame blocks the very next class and final VCSEC.
            publishProgress(snap)
            c.drivingUntilMs.value = nextDrivingUntil(
                c.drivingUntilMs.value, parseDriveSample(data), System.currentTimeMillis(),
            )
            if (category == Payloads.Category.DRIVE) {
                val sample = parseDriveSample(data)
                if (sample.speedKmh == null || sample.shift == null) {
                    hint.value = "DRIVE 未返回完整速度/档位，保留上次读数并暂停慢读"
                    readLog.record("DRIVE", started, "响应缺少有效速度/档位")
                    break
                }
            }
        }
        if (snap.mcuUpdatedAtMs == 0L && mcuFailures > 0) {
            val reason = lastMcuError ?: "无响应"
            error.value = if ("配对" in reason) {
                "车机读数被拒（$reason）：本机钥匙未被车辆认可，请到「设置」页重新发送配对请求"
            } else {
                "车机读数失败（$mcuFailures 类）：$reason"
            }
        }

        val body = readBody()
        if (body != null) snap = snap.withBody(body)
        return snap
    }

    private fun keeperName(climate: Vehicle.ClimateState): String? =
        when (climate.climateKeeperMode.typeCase) {
            Vehicle.ClimateState.ClimateKeeperMode.TypeCase.OFF -> "关闭"
            Vehicle.ClimateState.ClimateKeeperMode.TypeCase.ON -> "保持模式"
            Vehicle.ClimateState.ClimateKeeperMode.TypeCase.DOG -> "爱犬模式"
            Vehicle.ClimateState.ClimateKeeperMode.TypeCase.PARTY -> "露营模式"
            else -> null
        }

    private fun parseBleVehicleData(data: Vehicle.VehicleData): StatusSnapshot {
        // Only parsed readings renew the MCU clock; an empty reply must not freshen cached UI values.
        val now = System.currentTimeMillis()
        var snap = StatusSnapshot()
        if (data.hasChargeState()) {
            val charge = data.chargeState
            val chargingStateName = if (charge.hasChargingState()) {
                when (charge.chargingState.typeCase) {
                    Vehicle.ChargeState.ChargingState.TypeCase.CHARGING -> "充电中"
                    Vehicle.ChargeState.ChargingState.TypeCase.STARTING -> "启动中"
                    Vehicle.ChargeState.ChargingState.TypeCase.COMPLETE -> "已完成"
                    Vehicle.ChargeState.ChargingState.TypeCase.STOPPED -> "已停止"
                    Vehicle.ChargeState.ChargingState.TypeCase.DISCONNECTED -> "未连接"
                    Vehicle.ChargeState.ChargingState.TypeCase.NOPOWER -> "无电源"
                    Vehicle.ChargeState.ChargingState.TypeCase.CALIBRATING -> "校准中"
                    else -> null
                }
            } else null
            snap = snap.copy(
                batteryLevel = if (charge.hasBatteryLevel()) charge.batteryLevel else null,
                batteryRangeKm = if (charge.hasBatteryRange()) charge.batteryRange * MILES_TO_KM else null,
                chargeLimit = if (charge.hasChargeLimitSoc()) charge.chargeLimitSoc else null,
                chargingState = chargingStateName,
                chargePowerKw = if (charge.hasChargerPower()) charge.chargerPower else null,
                chargePortOpen = if (charge.hasChargePortDoorOpen()) charge.chargePortDoorOpen else null,
                // ↓ 同一帧里一直有、我们一直没解析的字段。里程类车机报英里，统一转 km。
                rangeRatedKm = opt(charge.hasBatteryRange()) { charge.batteryRange * MILES_TO_KM },
                rangeIdealKm = opt(charge.hasIdealBatteryRange()) { charge.idealBatteryRange * MILES_TO_KM },
                rangeEstKm = opt(charge.hasEstBatteryRange()) { charge.estBatteryRange * MILES_TO_KM },
                minutesToFullCharge = opt(charge.hasMinutesToFullCharge()) { charge.minutesToFullCharge },
                minutesToChargeLimit = opt(charge.hasMinutesToChargeLimit()) { charge.minutesToChargeLimit },
                chargerVoltage = opt(charge.hasChargerVoltage()) { charge.chargerVoltage.toFloat() },
                chargerActualAmps = opt(charge.hasChargerActualCurrent()) { charge.chargerActualCurrent },
                chargerPilotAmps = opt(charge.hasChargerPilotCurrent()) { charge.chargerPilotCurrent },
                chargerPhases = opt(charge.hasChargerPhases()) { charge.chargerPhases },
                energyAddedKwh = opt(charge.hasChargeEnergyAdded()) { charge.chargeEnergyAdded },
                addedRangeKm = opt(charge.hasChargeMilesAddedIdeal()) {
                    charge.chargeMilesAddedIdeal * MILES_TO_KM
                },
                offPeakEndsAtSec = opt(charge.hasOffPeakHoursEndTime()) {
                    charge.offPeakHoursEndTime.toLong()
                },
                scheduledChargingMode = opt(charge.hasScheduledChargingMode()) {
                    when (charge.scheduledChargingMode) {
                        Vehicle.ChargeState.ScheduledChargingMode.ScheduledChargingModeStartAt -> "定时充电：从"
                        Vehicle.ChargeState.ScheduledChargingMode.ScheduledChargingModeDepartBy -> "定时充电：出发前"
                        else -> "未设定时"
                    }
                },
                scheduledChargeAtMinute = opt(charge.hasScheduledChargingStartTimeMinutes()) {
                    charge.scheduledChargingStartTimeMinutes.toInt()
                },
                scheduledDepartAtMinute = opt(charge.hasScheduledDepartureTimeMinutes()) {
                    charge.scheduledDepartureTimeMinutes.toInt()
                },
                preconditionOnDeparture = opt(charge.hasPreconditioningEnabled()) {
                    charge.preconditioningEnabled
                },
                chargePortColdWeather = opt(charge.hasChargePortColdWeatherMode()) {
                    charge.chargePortColdWeatherMode
                },
                cableUnlatched = opt(charge.hasChargeCableUnlatched()) { charge.chargeCableUnlatched },
                homeLat = opt(charge.hasHomeLocation()) { charge.homeLocation.latitude },
                homeLon = opt(charge.hasHomeLocation()) { charge.homeLocation.longitude },
                workLat = opt(charge.hasWorkLocation()) { charge.workLocation.latitude },
                workLon = opt(charge.hasWorkLocation()) { charge.workLocation.longitude },
                powershareAllowed = opt(charge.hasPowershareFeatureAllowed()) {
                    charge.powershareFeatureAllowed
                },
                powershareEnabled = opt(charge.hasPowershareFeatureEnabled()) {
                    charge.powershareFeatureEnabled
                },
                powershareStatus = opt(charge.hasPowershareStatus()) {
                    when (charge.powershareStatus) {
                        Vehicle.ChargeState.PowershareStatus.PowershareStatusActive -> "放电中"
                        Vehicle.ChargeState.PowershareStatus.PowershareStatusInit -> "正在建立"
                        Vehicle.ChargeState.PowershareStatus.PowershareStatusHandshaking -> "握手"
                        else -> "未放电"
                    }
                },
                powershareLoadKw = opt(charge.hasPowershareInstantaneousLoadKw()) {
                    charge.powershareInstantaneousLoadKw
                },
                outletSocLimit = opt(charge.hasOutletSocLimit()) { charge.outletSocLimit },
            )
        }
        if (data.hasClimateState()) {
            val climate = data.climateState
            snap = snap.copy(
                insideTemp = if (climate.hasInsideTempCelsius()) climate.insideTempCelsius else null,
                outsideTemp = if (climate.hasOutsideTempCelsius()) climate.outsideTempCelsius else null,
                climateOn = if (climate.hasIsClimateOn()) climate.isClimateOn else null,
                driverTemp = if (climate.hasDriverTempSetting()) climate.driverTempSetting else null,
                hvacAuto = if (climate.hasHvacAutoRequest()) {
                    climate.hvacAutoRequest == Vehicle.ClimateState.HvacAutoRequest.HvacAutoRequestOn
                } else {
                    null
                },
                fanLevel = if (climate.hasFanStatus()) climate.fanStatus else null,
                seatHeatLeft = if (climate.hasSeatHeaterLeft()) climate.seatHeaterLeft else null,
                seatHeatRight = if (climate.hasSeatHeaterRight()) climate.seatHeaterRight else null,
                seatCoolLeft = if (climate.hasSeatFanFrontLeft()) climate.seatFanFrontLeft else null,
                seatCoolRight = if (climate.hasSeatFanFrontRight()) climate.seatFanFrontRight else null,
                swHeater = if (climate.hasSteeringWheelHeater()) climate.steeringWheelHeater else null,
                // MAX 除霜的回读一直错用了 is_preconditioning（那是"座舱预设中"，跟除霜无关），
                // 所以按下 MAX 除霜后这颗灯永远不亮。除霜的真值是前/后除霜器两个 bool。
                frontDefrosterOn = opt(climate.hasIsFrontDefrosterOn()) { climate.isFrontDefrosterOn },
                rearDefrosterOn = opt(climate.hasIsRearDefrosterOn()) { climate.isRearDefrosterOn },
                maxDefrost = opt(
                    climate.hasIsFrontDefrosterOn() || climate.hasIsRearDefrosterOn()
                ) {
                    (if (climate.hasIsFrontDefrosterOn()) climate.isFrontDefrosterOn else false) ||
                        (if (climate.hasIsRearDefrosterOn()) climate.isRearDefrosterOn else false)
                },
                preconditioningActive = opt(climate.hasIsPreconditioning()) { climate.isPreconditioning },
                keeper = keeperName(climate),
                bioweaponOn = if (climate.hasBioweaponModeOn()) climate.bioweaponModeOn else null,
                seatHeatRearLeft = opt(climate.hasSeatHeaterRearLeft()) { climate.seatHeaterRearLeft },
                seatHeatRearRight = opt(climate.hasSeatHeaterRearRight()) { climate.seatHeaterRearRight },
                seatHeatRearCenter = opt(climate.hasSeatHeaterRearCenter()) { climate.seatHeaterRearCenter },
                seatHeatThirdLeft = opt(climate.hasSeatHeaterThirdRowLeft()) {
                    climate.seatHeaterThirdRowLeft
                },
                seatHeatThirdRight = opt(climate.hasSeatHeaterThirdRowRight()) {
                    climate.seatHeaterThirdRowRight
                },
                mirrorHeater = opt(climate.hasSideMirrorHeaters()) { climate.sideMirrorHeaters },
                wiperBladeHeater = opt(climate.hasWiperBladeHeater()) { climate.wiperBladeHeater },
                batteryHeater = opt(climate.hasBatteryHeater()) { climate.batteryHeater },
                batteryHeaterNoPower = opt(climate.hasBatteryHeaterNoPower()) {
                    climate.batteryHeaterNoPower
                },
                autoSeatClimateLeft = opt(climate.hasAutoSeatClimateLeft()) { climate.autoSeatClimateLeft },
                autoSeatClimateRight = opt(climate.hasAutoSeatClimateRight()) {
                    climate.autoSeatClimateRight
                },
                autoSteeringHeat = opt(climate.hasAutoSteeringWheelHeat()) {
                    climate.autoSteeringWheelHeat
                },
                steeringHeatLevel = opt(climate.hasSteeringWheelHeatLevel()) {
                    climate.steeringWheelHeatLevel.number
                },
                copAllowed = opt(climate.hasAllowCabinOverheatProtection()) {
                    climate.allowCabinOverheatProtection
                },
                copState = opt(climate.hasCabinOverheatProtection()) {
                    climate.cabinOverheatProtection.name
                },
                copCooling = opt(climate.hasCabinOverheatProtectionActivelyCooling()) {
                    climate.cabinOverheatProtectionActivelyCooling
                },
            )
        }
        snap = applyStatusDrive(snap, data, now)
        // 哨兵状态在 ClosuresState 里（需要单独的 CLOSURES 类别请求）。字段级时钟必须有：
        // mcuUpdatedAtMs 会被任意一类的成功解析续命（连只读 CHARGE 的唤醒探针都会），
        // 没有自己的时间戳，几小时前的哨兵旧值会伪装成新鲜值参与按钮互斥。
        if (data.hasClosuresState()) {
            val closures = data.closuresState
            if (closures.hasSentryModeState()) {
                val sentry = when (closures.sentryModeState.typeCase) {
                    Vehicle.ClosuresState.SentryModeState.TypeCase.OFF,
                    Vehicle.ClosuresState.SentryModeState.TypeCase.IDLE,
                    -> false
                    Vehicle.ClosuresState.SentryModeState.TypeCase.ARMED,
                    Vehicle.ClosuresState.SentryModeState.TypeCase.AWARE,
                    Vehicle.ClosuresState.SentryModeState.TypeCase.QUIET,
                    Vehicle.ClosuresState.SentryModeState.TypeCase.PANIC,
                    -> true
                    else -> null
                }
                if (sentry != null) snap = snap.copy(sentryOn = sentry, sentryAtMs = now)
            }
            val cl = data.closuresState
            snap = snap.copy(
                sunRoofPercent = opt(cl.hasSunRoofPercentOpen()) { cl.sunRoofPercentOpen },
                doorDriverFront = opt(cl.hasDoorOpenDriverFront()) { cl.doorOpenDriverFront },
                doorDriverRear = opt(cl.hasDoorOpenDriverRear()) { cl.doorOpenDriverRear },
                doorPassengerFront = opt(cl.hasDoorOpenPassengerFront()) { cl.doorOpenPassengerFront },
                doorPassengerRear = opt(cl.hasDoorOpenPassengerRear()) { cl.doorOpenPassengerRear },
                windowDriverFront = opt(cl.hasWindowOpenDriverFront()) { cl.windowOpenDriverFront },
                windowDriverRear = opt(cl.hasWindowOpenDriverRear()) { cl.windowOpenDriverRear },
                windowPassengerFront = opt(cl.hasWindowOpenPassengerFront()) {
                    cl.windowOpenPassengerFront
                },
                windowPassengerRear = opt(cl.hasWindowOpenPassengerRear()) { cl.windowOpenPassengerRear },
                remoteStart = opt(cl.hasRemoteStart()) { cl.remoteStart },
                valetMode = opt(cl.hasValetMode()) { cl.valetMode },
            )
        }
        if (data.hasLocationState()) {
            val loc = data.locationState
            snap = snap.copy(
                latitude = when {
                    loc.hasCorrectedLatitude() -> loc.correctedLatitude
                    loc.hasLatitude() -> loc.latitude
                    loc.hasNativeLatitude() -> loc.nativeLatitude
                    else -> null
                },
                longitude = when {
                    loc.hasCorrectedLongitude() -> loc.correctedLongitude
                    loc.hasLongitude() -> loc.longitude
                    loc.hasNativeLongitude() -> loc.nativeLongitude
                    else -> null
                },
                heading = if (loc.hasHeading()) loc.heading else null,
            )
        }
        return if (snap == StatusSnapshot()) snap else snap.copy(updatedAtMs = now, mcuUpdatedAtMs = now)
    }
}

/** Overlay one per-category read onto the accumulator: 本轮没答上的字段沿用旧值与旧时钟。 */
internal fun StatusSnapshot.mergedInto(base: StatusSnapshot): StatusSnapshot = copy(
    batteryLevel = batteryLevel ?: base.batteryLevel,
    batteryRangeKm = batteryRangeKm ?: base.batteryRangeKm,
    chargeLimit = chargeLimit ?: base.chargeLimit,
    chargingState = chargingState ?: base.chargingState,
    chargePowerKw = chargePowerKw ?: base.chargePowerKw,
    insideTemp = insideTemp ?: base.insideTemp,
    outsideTemp = outsideTemp ?: base.outsideTemp,
    climateOn = climateOn ?: base.climateOn,
    driverTemp = driverTemp ?: base.driverTemp,
    hvacAuto = hvacAuto ?: base.hvacAuto,
    fanLevel = fanLevel ?: base.fanLevel,
    speedKmh = speedKmh ?: base.speedKmh,
    driveSpeedAtMs = driveSpeedAtMs ?: base.driveSpeedAtMs,
    driveShiftAtMs = driveShiftAtMs ?: base.driveShiftAtMs,
    powerKw = powerKw ?: base.powerKw,
    latitude = latitude ?: base.latitude,
    longitude = longitude ?: base.longitude,
    heading = heading ?: base.heading,
    shiftState = shiftState ?: base.shiftState,
    seatHeatLeft = seatHeatLeft ?: base.seatHeatLeft,
    seatHeatRight = seatHeatRight ?: base.seatHeatRight,
    seatCoolLeft = seatCoolLeft ?: base.seatCoolLeft,
    seatCoolRight = seatCoolRight ?: base.seatCoolRight,
    swHeater = swHeater ?: base.swHeater,
    maxDefrost = maxDefrost ?: base.maxDefrost,
    keeper = keeper ?: base.keeper,
    bioweaponOn = bioweaponOn ?: base.bioweaponOn,
    sentryOn = sentryOn ?: base.sentryOn,
    sentryAtMs = sentryAtMs ?: base.sentryAtMs,
    rangeRatedKm = rangeRatedKm ?: base.rangeRatedKm,
    rangeIdealKm = rangeIdealKm ?: base.rangeIdealKm,
    rangeEstKm = rangeEstKm ?: base.rangeEstKm,
    minutesToFullCharge = minutesToFullCharge ?: base.minutesToFullCharge,
    minutesToChargeLimit = minutesToChargeLimit ?: base.minutesToChargeLimit,
    chargerVoltage = chargerVoltage ?: base.chargerVoltage,
    chargerActualAmps = chargerActualAmps ?: base.chargerActualAmps,
    chargerPilotAmps = chargerPilotAmps ?: base.chargerPilotAmps,
    chargerPhases = chargerPhases ?: base.chargerPhases,
    energyAddedKwh = energyAddedKwh ?: base.energyAddedKwh,
    addedRangeKm = addedRangeKm ?: base.addedRangeKm,
    offPeakEndsAtSec = offPeakEndsAtSec ?: base.offPeakEndsAtSec,
    scheduledChargingMode = scheduledChargingMode ?: base.scheduledChargingMode,
    scheduledChargeAtMinute = scheduledChargeAtMinute ?: base.scheduledChargeAtMinute,
    scheduledDepartAtMinute = scheduledDepartAtMinute ?: base.scheduledDepartAtMinute,
    preconditionOnDeparture = preconditionOnDeparture ?: base.preconditionOnDeparture,
    batteryHeater = batteryHeater ?: base.batteryHeater,
    batteryHeaterNoPower = batteryHeaterNoPower ?: base.batteryHeaterNoPower,
    chargePortColdWeather = chargePortColdWeather ?: base.chargePortColdWeather,
    cableUnlatched = cableUnlatched ?: base.cableUnlatched,
    seatHeatRearLeft = seatHeatRearLeft ?: base.seatHeatRearLeft,
    seatHeatRearRight = seatHeatRearRight ?: base.seatHeatRearRight,
    seatHeatRearCenter = seatHeatRearCenter ?: base.seatHeatRearCenter,
    seatHeatThirdLeft = seatHeatThirdLeft ?: base.seatHeatThirdLeft,
    seatHeatThirdRight = seatHeatThirdRight ?: base.seatHeatThirdRight,
    mirrorHeater = mirrorHeater ?: base.mirrorHeater,
    wiperBladeHeater = wiperBladeHeater ?: base.wiperBladeHeater,
    preconditioningActive = preconditioningActive ?: base.preconditioningActive,
    autoSeatClimateLeft = autoSeatClimateLeft ?: base.autoSeatClimateLeft,
    autoSeatClimateRight = autoSeatClimateRight ?: base.autoSeatClimateRight,
    autoSteeringHeat = autoSteeringHeat ?: base.autoSteeringHeat,
    steeringHeatLevel = steeringHeatLevel ?: base.steeringHeatLevel,
    frontDefrosterOn = frontDefrosterOn ?: base.frontDefrosterOn,
    rearDefrosterOn = rearDefrosterOn ?: base.rearDefrosterOn,
    copAllowed = copAllowed ?: base.copAllowed,
    copState = copState ?: base.copState,
    copCooling = copCooling ?: base.copCooling,
    powershareAllowed = powershareAllowed ?: base.powershareAllowed,
    powershareEnabled = powershareEnabled ?: base.powershareEnabled,
    powershareStatus = powershareStatus ?: base.powershareStatus,
    powershareLoadKw = powershareLoadKw ?: base.powershareLoadKw,
    outletSocLimit = outletSocLimit ?: base.outletSocLimit,
    sunRoofPercent = sunRoofPercent ?: base.sunRoofPercent,
    doorDriverFront = doorDriverFront ?: base.doorDriverFront,
    doorDriverRear = doorDriverRear ?: base.doorDriverRear,
    doorPassengerFront = doorPassengerFront ?: base.doorPassengerFront,
    doorPassengerRear = doorPassengerRear ?: base.doorPassengerRear,
    windowDriverFront = windowDriverFront ?: base.windowDriverFront,
    windowDriverRear = windowDriverRear ?: base.windowDriverRear,
    windowPassengerFront = windowPassengerFront ?: base.windowPassengerFront,
    windowPassengerRear = windowPassengerRear ?: base.windowPassengerRear,
    remoteStart = remoteStart ?: base.remoteStart,
    valetMode = valetMode ?: base.valetMode,
    homeLat = homeLat ?: base.homeLat,
    homeLon = homeLon ?: base.homeLon,
    workLat = workLat ?: base.workLat,
    workLon = workLon ?: base.workLon,
    mcuUpdatedAtMs = maxOf(mcuUpdatedAtMs, base.mcuUpdatedAtMs),
)

/** 车机这一轮给了才取值；没给返回 null，合并时保留上一轮的值。 */
private inline fun <T> opt(present: Boolean, value: () -> T): T? = if (present) value() else null

/** 车机里程类字段一律英里（速度、续航、充电增加里程），界面统一显示 km。 */
private const val MILES_TO_KM = 1.609344f
