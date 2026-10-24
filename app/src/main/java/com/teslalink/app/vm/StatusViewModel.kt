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
) {
    fun shiftIsMoving(): Boolean = shiftState == "R" || shiftState == "N" || shiftState == "D"

    /**
     * 行驶判断用 60s 窗口（闸门宁可多灰一会儿也不能误放行），与控制页 6s 的"车机新鲜"窗口区分。
     */
    fun recentlyMoving(now: Long = System.currentTimeMillis()): Boolean =
        mcuUpdatedAtMs > 0 && now - mcuUpdatedAtMs < 60_000 &&
            ((speedKmh ?: 0f) > 2f || shiftIsMoving())

    // 行驶中 VCSEC 对开闭件一律报 UNKNOWN，而行驶中物理上不可能开着 —— 显示层推断为关。
    fun rearTrunkShown(moving: Boolean): Boolean? = rearTrunkOpen ?: if (moving) false else null

    fun frunkShown(moving: Boolean): Boolean? = frunkOpen ?: if (moving) false else null

    fun chargePortShown(moving: Boolean): Boolean? = chargePortOpen ?: if (moving) false else null
}

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

    /**
     * Wake-ups are expensive for the car: with the head unit asleep one read costs a failed
     * handshake (8 s) plus a wake plus a retry (30 s). The auto poller gets a single probe, then
     * reads only the unauthenticated controller until he taps 刷新.
     */
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
                // 省下链路给车速。两个行驶信号源：仪表页的实时车速、本页快照里 60s 窗口内的移动。
                val moving = c.vehicleMoving.value || snapshot.value?.recentlyMoving() == true
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
        if (bodyInFlight) return
        if (!force && c.connection.exclusive.value) return
        if (c.connection.state.value != VehicleConnection.State.CONNECTED) return
        viewModelScope.launch {
            bodyInFlight = true
            val body = try {
                c.connection.bodyControllerState()
            } catch (e: Exception) {
                null
            }
            if (body != null) {
                snapshot.update { (it ?: StatusSnapshot()).withBody(body) }
            }
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
        // 他 2026-10-17 定的死规矩：车速新鲜度压倒一切。行驶中（车速道活着，或 60s 内车在动）
        // 不跑五类大类轮——每类都是一次独立请求，全排在同一条 infotainmentMutex 上，
        // 一轮就能把仪表的 DRIVE 读堵住好几秒。写命令（空调/温度等小请求）不受此限。
        if (c.vehicleMoving.value || snapshot.value?.recentlyMoving() == true) {
            hint.value = "行驶中：暂停读取车机大类（带宽让给车速），停车后自动恢复"
            return
        }
        viewModelScope.launch {
            mcuInFlight = true
            refreshing.value = true
            error.value = null
            try {
                if (c.connection.state.value != VehicleConnection.State.CONNECTED) {
                    error.value = "未连接：状态读取需要手机在车旁并完成 BLE 连接"
                    return@launch
                }
                val mcuReady = c.connection.mcuSessionReady.value
                if (force || mcuReady || autoMcuProbes < 1) {
                    if (!mcuReady) autoMcuProbes++
                    hint.value = null
                    val fresh = bleSnapshot()
                    // The head unit answered: hand the auto poller its probe budget back.
                    if (fresh.mcuUpdatedAtMs > 0) autoMcuProbes = 0
                    // Atomic merge: this round trip takes seconds, during which the 3s body poll may
                    // have written lock/closure values. Assigning the whole snapshot would roll them back.
                    snapshot.update { prev -> mergeSnapshot(prev, fresh) }
                } else {
                    refreshBody(force = true)
                    // Keep the reason visible instead of letting the next 10 s round erase it.
                    val why = lastMcuError?.let { "（上次尝试：$it）" }.orEmpty()
                    hint.value = "车机未唤醒：电量/温度/位置需要车机响应$why，点右上角「刷新」再试一次"
                }
            } catch (e: Exception) {
                error.value = e.message
            } finally {
                refreshing.value = false
                mcuInFlight = false
            }
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

    private suspend fun bleSnapshot(): StatusSnapshot {
        // One category per request: a multi-category response exceeds the negotiated MTU and the
        // vehicle rejects it wholesale (RESPONSE_MTU_EXCEEDED). A failed category just stays null.
        // 顺序按"控制页的按钮闸门"排：CLOSURES 带哨兵状态，放第一个，读到就能放行按钮；
        // DRIVE/LOCATION 只喂车辆详情，放最后。
        var snap = StatusSnapshot()
        lastMcuError = null
        var mcuFailures = 0
        for (category in listOf(
            Payloads.Category.CLOSURES,
            Payloads.Category.CLIMATE,
            Payloads.Category.CHARGE,
            Payloads.Category.DRIVE,
            Payloads.Category.LOCATION,
        )) {
            // 车开起来了（比如他在大类轮没跑完时切回仪表）：立刻收手，整条链路让给车速道。
            if (c.vehicleMoving.value || snapshot.value?.recentlyMoving() == true) break
            val data = try {
                c.connection.vehicleData(listOf(category))
            } catch (e: Exception) {
                mcuFailures++
                if (lastMcuError == null) lastMcuError = e.message
                // Session-level failure: the head unit is asleep or refuses our key, and the
                // remaining categories would each repeat a ~40 s wake-and-retry. Cut the round short
                // and report the reason instead of leaving the page on a silent wall of "--".
                if (!c.connection.mcuSessionReady.value) break
                null
            } ?: continue
            snap = parseBleVehicleData(data).mergedInto(snap)
            // 每读到一类就立刻发布：整轮要好几秒，等全部读完才刷新，界面上就是"状态来得特别慢"。
            publishProgress(snap)
        }
        if (snap.mcuUpdatedAtMs == 0L && mcuFailures > 0) {
            val reason = lastMcuError ?: "无响应"
            error.value = if ("配对" in reason) {
                "车机读数被拒（$reason）：本机钥匙未被车辆认可，请到「车辆」页重新配对"
            } else {
                "车机读数失败（$mcuFailures 类）：$reason"
            }
        }

        val body = try {
            c.connection.bodyControllerState()
        } catch (e: Exception) {
            null
        }
        if (body != null) snap = snap.withBody(body)
        return snap
    }

    /** Overlay one per-category read onto the accumulator. */
    private fun StatusSnapshot.mergedInto(base: StatusSnapshot): StatusSnapshot = copy(
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
        mcuUpdatedAtMs = maxOf(mcuUpdatedAtMs, base.mcuUpdatedAtMs),
    )

    private fun keeperName(climate: Vehicle.ClimateState): String? =
        when (climate.climateKeeperMode.typeCase) {
            Vehicle.ClimateState.ClimateKeeperMode.TypeCase.OFF -> "关闭"
            Vehicle.ClimateState.ClimateKeeperMode.TypeCase.ON -> "保持模式"
            Vehicle.ClimateState.ClimateKeeperMode.TypeCase.DOG -> "爱犬模式"
            Vehicle.ClimateState.ClimateKeeperMode.TypeCase.PARTY -> "露营模式"
            else -> null
        }

    private fun parseBleVehicleData(data: Vehicle.VehicleData): StatusSnapshot {
        // Reaching here means the head unit answered: mark the MCU clock so callers can tell a
        // live reading from one carried over from an earlier round.
        val now = System.currentTimeMillis()
        var snap = StatusSnapshot(updatedAtMs = now, mcuUpdatedAtMs = now)
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
                batteryRangeKm = if (charge.hasBatteryRange()) charge.batteryRange * 1.609f else null,
                chargeLimit = if (charge.hasChargeLimitSoc()) charge.chargeLimitSoc else null,
                chargingState = chargingStateName,
                chargePowerKw = if (charge.hasChargerPower()) charge.chargerPower else null,
                chargePortOpen = if (charge.hasChargePortDoorOpen()) charge.chargePortDoorOpen else null,
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
                maxDefrost = if (climate.hasIsPreconditioning()) climate.isPreconditioning else null,
                keeper = keeperName(climate),
                bioweaponOn = if (climate.hasBioweaponModeOn()) climate.bioweaponModeOn else null,
            )
        }
        if (data.hasDriveState()) {
            val drive = data.driveState
            snap = snap.copy(
                speedKmh = when {
                    drive.hasSpeedFloat() -> drive.speedFloat * 1.609344f
                    drive.hasSpeed() -> drive.speed * 1.609344f
                    else -> null
                },
                powerKw = if (drive.hasPower()) drive.power else null,
                shiftState = when (drive.shiftState.typeCase) {
                    Vehicle.ShiftState.TypeCase.P -> "P"
                    Vehicle.ShiftState.TypeCase.R -> "R"
                    Vehicle.ShiftState.TypeCase.N -> "N"
                    Vehicle.ShiftState.TypeCase.D -> "D"
                    else -> null
                },
            )
        }
        // 哨兵状态在 ClosuresState 里（需要单独的 CLOSURES 类别请求）。
        if (data.hasClosuresState()) {
            val closures = data.closuresState
            if (closures.hasSentryModeState()) {
                snap = snap.copy(
                    sentryOn = when (closures.sentryModeState.typeCase) {
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
                )
            }
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
        return snap
    }
}
