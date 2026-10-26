package com.teslalink.app.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.teslalink.app.AppContainer
import com.teslalink.app.vehicle.VehicleConnection
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** BLE link + command execution + key pairing. */
class LinkViewModel(private val c: AppContainer) : ViewModel() {

    enum class PairingPhase { IDLE, WAITING_LINK, SENDING, WAITING_CONFIRM, PAIRED, FAILED }

    data class LogEntry(
        val time: Long,
        val label: String,
        val ok: Boolean,
        val message: String?,
        val latencyMs: Long,
    )

    val vin = MutableStateFlow("")
    val state: StateFlow<VehicleConnection.State> = c.connection.state
    val keyPaired: StateFlow<Boolean> = c.connection.keyPaired

    /** False while CarServer (climate/charge/telemetry) cannot reach the head unit. */
    val mcuReady: StateFlow<Boolean> = c.connection.mcuSessionReady
    val log = MutableStateFlow<List<LogEntry>>(emptyList())
    val pairingPhase = MutableStateFlow(PairingPhase.IDLE)

    /** 配对流程最近一条说明：车辆对配对请求的回执（含中文解释），或这次没发出的原因。null = 还没有。 */
    val pairingAck = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)

    /**
     * Local public key, owned by the VM so the 设置 page can never show a value from before a
     * rotation completed. Declared before init: the init coroutines write it.
     */
    val publicKeyHex = MutableStateFlow("…")

    // ↓↓↓ 必须声明在 init 之前：viewModelScope 用 Main.immediate，init 里启动的收集器
    // 会在属性初始化完成前同步执行，读到未初始化属性就是打开即闪退。 ↓↓↓
    private var reconnectJob: Job? = null
    private val manualDisconnect = MutableStateFlow(false)

    /** True while the app is trying to bring the link back on its own. */
    val autoReconnecting = MutableStateFlow(false)

    /** 是否成功连上过车，持久化。 */
    val everConnected = MutableStateFlow(false)

    /**
     * 车辆是否确认过本机公钥在册（配对完成的硬证据：白名单校验/鉴权命令成功）。
     * 决定是否跳过首次引导：true 时冷启动直接进仪表，false 才跳设置。
     */
    val keyPairedEver = MutableStateFlow(false)

    /** prefs 异步读取完成前不许做任何跳转决定——否则冷启动永远先看到默认 false。 */
    val prefsLoaded = MutableStateFlow(false)

    /** 开发者模式：控制底栏是否显示「调试」页，默认关闭，持久化。 */
    val devMode = MutableStateFlow(false)

    fun setDevMode(on: Boolean) {
        devMode.value = on
        viewModelScope.launch { c.prefs.putString("dev_mode", if (on) "1" else "0") }
    }

    /** 车认下这把公钥 = 进入"日常"模式；未识别过才需要引导。 */
    fun resetOnboarding() {
        keyPairedEver.value = false
        everConnected.value = false
        viewModelScope.launch {
            c.prefs.putString("key_paired_ever", "0")
            c.prefs.putString("ever_connected", "0")
        }
    }

    private fun markKeyPaired() {
        if (keyPairedEver.value) return
        keyPairedEver.value = true
        viewModelScope.launch { c.prefs.putString("key_paired_ever", "1") }
    }

    init {
        viewModelScope.launch {
            vin.value = sanitizeVin(c.prefs.getString("vin") ?: "")
            lastCommittedFullVin = vin.value.takeIf { it.length == 17 }
            everConnected.value = c.prefs.getString("ever_connected") == "1"
            keyPairedEver.value = c.prefs.getString("key_paired_ever") == "1"
            devMode.value = c.prefs.getString("dev_mode") == "1"
            prefsLoaded.value = true
            loadPublicKey()
            // VIN 是异步读出来的：读完之前 shouldAutoReconnect() 恒为 false，上面的状态
            // collector 早已错过"启动时就是 DISCONNECTED"那一次发射，于是冷启动不再自动连。
            // 读完这里主动补一次——这就是"打开 app 自动尝试连接"的入口。
            if (c.connection.state.value == VehicleConnection.State.DISCONNECTED &&
                shouldAutoReconnect()
            ) {
                startAutoReconnect()
            }
        }
        // 车认下这把公钥就算匹配过，永久记住（换钥匙/换车时清）。
        viewModelScope.launch {
            c.connection.keyPaired.collect { paired -> if (paired) markKeyPaired() }
        }
        // 自动重连：链路掉线（车休眠、走出范围、被挤占）后在亮屏期间持续尝试恢复，
        // 不需要每次手动点「扫描并连接」。冷启动打开 app 时同样由它把链路拉起来。
        viewModelScope.launch {
            c.connection.state.collect { state ->
                when (state) {
                    VehicleConnection.State.CONNECTED -> {
                        manualDisconnect.value = false
                        autoReconnecting.value = false
                        if (!everConnected.value) {
                            everConnected.value = true
                            viewModelScope.launch { c.prefs.putString("ever_connected", "1") }
                        }
                    }
                    VehicleConnection.State.DISCONNECTED -> {
                        if (shouldAutoReconnect()) startAutoReconnect()
                    }
                    else -> {}
                }
            }
        }
        // 回到前台也要补一次检查：后台期间掉线不会经过上面的状态回调。
        viewModelScope.launch {
            c.appInForeground.collect { foreground ->
                if (foreground && c.connection.state.value == VehicleConnection.State.DISCONNECTED &&
                    shouldAutoReconnect()
                ) {
                    startAutoReconnect()
                }
            }
        }
    }

    /**
     * 前台 **或 行驶闩有效** 才自动重连：D/R 中息屏/后台掉链（隧道、出范围）要把链路接回来，
     * 否则轨迹就断在半路；P 档停着时后台绝不重连（别反复唤醒车机）。
     * 行驶闩由车速道的新鲜数据维护、90s 过期——断链后最多试着恢复 90 秒。
     */
    private fun shouldAutoReconnect(): Boolean =
        !manualDisconnect.value && vin.value.length == 17 &&
            (c.appInForeground.value || c.drivingUntilMs.value > System.currentTimeMillis())

    private fun startAutoReconnect() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = viewModelScope.launch {
            autoReconnecting.value = true
            var attempt = 0
            var logged = false
            while (isActive &&
                c.connection.state.value == VehicleConnection.State.DISCONNECTED &&
                shouldAutoReconnect()
            ) {
                attempt++
                delay(minOf(15_000L, 2_000L * attempt))
                if (c.connection.state.value != VehicleConnection.State.DISCONNECTED) break
                val result = runCatching { c.connection.connect(vin.value) }
                if (result.isFailure && !logged) {
                    logged = true
                    // 真实失败原因必须留在日志里（权限缺失/扫描超时/连接满各是不同的处置），
                    // 固定一句"可能休眠或不在范围"会把他往错的方向带。
                    val e = result.exceptionOrNull()
                    appendLog(
                        "自动重连",
                        false,
                        "第 $attempt 次未成功：${e?.message ?: e?.javaClass?.simpleName ?: "未知"}" +
                            "；车辆可能休眠或不在范围，会持续重试",
                        0,
                    )
                }
            }
            autoReconnecting.value = false
        }
    }

    private suspend fun loadPublicKey() {
        runCatching {
            c.keyManager.publicBytes()
                .joinToString("") { "%02x".format(it) }
                .chunked(32)
                .joinToString("\n")
        }.onSuccess { publicKeyHex.value = it }
            .onFailure { publicKeyHex.value = "无法读取本机钥匙：${it.message}" }
    }

    /** VINs are alphanumeric; anything else is a paste artifact that would break name hash + AAD. */
    private fun sanitizeVin(raw: String): String =
        raw.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }

    /**
     * 最近一次"承认过"的完整 VIN（17 位）。换车清理的判据不能拿当前输入框内容比——
     * 逐字编辑（删到 16 位再补齐）时中间态会把 17 位基准冲掉，新 VIN 永远触发不了清理，
     * 旧车的 MAC/引导状态就带到新车上了。
     */
    private var lastCommittedFullVin: String? = null

    fun setVin(value: String) {
        val cleaned = sanitizeVin(value)
        if (cleaned == vin.value) return
        vin.value = cleaned
        viewModelScope.launch {
            c.prefs.putString("vin", cleaned)
            // Only a completed switch counts: setVin fires on every keystroke, and treating a
            // partially typed VIN as a new car would drop the link and wipe the saved address.
            val switched = cleaned.length == 17 &&
                lastCommittedFullVin != null &&
                cleaned != lastCommittedFullVin
            if (cleaned.length == 17) lastCommittedFullVin = cleaned
            if (switched) {
                // Sessions, the remembered MAC and pairing all belong to the old VIN's car.
                c.connection.disconnect()
                c.prefs.remove("ble_direct_addr")
                // 换车 = 这把钥匙对新车还没配对过，重新走引导。
                keyPairedEver.value = false
                c.prefs.putString("key_paired_ever", "0")
                pairingPhase.value = PairingPhase.IDLE
                pairingAck.value = null
                appendLog("已切换 VIN", true, "旧链路与已记住的地址已清除，请重新连接", 0)
            }
        }
    }

    private var connectInFlight = false

    fun connect() {
        if (connectInFlight) return
        connectInFlight = true
        manualDisconnect.value = false
        reconnectJob?.cancel()
        reconnectJob = null
        autoReconnecting.value = false
        launchLogged("连接") {
            try {
                c.connection.connect(vin.value)
            } finally {
                connectInFlight = false
            }
        }
    }

    fun setCustomBleName(name: String?) {
        viewModelScope.launch { c.prefs.putString("ble_name_override", name?.trim()?.ifEmpty { null }) }
    }

    /** Pin a MAC so future connects skip discovery (e.g. while the car doesn't advertise). */
    fun rememberAddress(address: String) {
        viewModelScope.launch {
            c.prefs.putString("ble_direct_addr", address.uppercase())
            appendLog("已记住设备 $address", true, null, 0)
        }
    }

    fun forgetAddress() {
        viewModelScope.launch {
            c.prefs.remove("ble_direct_addr")
            appendLog("已清除记住的设备地址", true, null, 0)
        }
    }

    fun disconnect() {
        // 用户意图优先：断开之后自动重连不许再把链路拉起来。
        manualDisconnect.value = true
        reconnectJob?.cancel()
        reconnectJob = null
        autoReconnecting.value = false
        c.connection.disconnect()
        pairingPhase.value = PairingPhase.IDLE
        pairingAck.value = null
    }

    private fun launchLogged(label: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            busy.value = true
            val start = System.currentTimeMillis()
            try {
                block()
                appendLog(label, true, null, System.currentTimeMillis() - start)
            } catch (e: Exception) {
                appendLog(label, false, e.message, System.currentTimeMillis() - start)
            } finally {
                busy.value = false
            }
        }
    }

    private fun command(label: String, block: suspend () -> VehicleConnection.CommandResult) {
        viewModelScope.launch {
            busy.value = true
            try {
                // Hold the single BLE pipe for user-initiated work: otherwise the 250 ms
                // telemetry queue inflates the latency we are trying to measure.
                val result = c.connection.exclusiveAction { block() }
                appendLog(label, result.ok, result.message, result.latencyMs)
            } catch (e: Exception) {
                appendLog(label, false, e.message, 0)
            } finally {
                busy.value = false
            }
        }
    }

    private fun appendLog(label: String, ok: Boolean, message: String?, latencyMs: Long) {
        com.teslalink.app.debug.DebugLog.log(
            "ui",
            "[$label] ok=$ok ${latencyMs}ms${message?.let { " :: $it" } ?: ""}"
        )
        log.value = listOf(LogEntry(System.currentTimeMillis(), label, ok, message, latencyMs)) +
            log.value.take(30)
    }

    // ---- Pairing -----------------------------------------------------------

    private var pairingJob: Job? = null

    fun sendPairingRequest() {
        // One loop at a time: two 120s polls racing on the same phase/key state produces
        // confusing results exactly when he can't afford another car trip.
        if (pairingJob?.isActive == true) return
        pairingJob = viewModelScope.launch {
            pairingPhase.value = PairingPhase.WAITING_LINK
            pairingAck.value = null
            try {
                awaitLinkForPairing()
                pairingPhase.value = PairingPhase.SENDING
                c.connection.exclusiveAction { c.connection.sendAddKeyRequest() }.also { pairingAck.value = it }
                pairingPhase.value = PairingPhase.WAITING_CONFIRM
                val deadline = System.currentTimeMillis() + 120_000
                while (System.currentTimeMillis() < deadline) {
                    delay(3_000)
                    // Each check owns the link while it runs, so the dashboard/status lanes can't
                    // interleave a handshake of their own and make this answer ambiguous.
                    val paired = c.connection.exclusiveAction { c.connection.isKeyPaired() }
                    if (paired) {
                        pairingPhase.value = PairingPhase.PAIRED
                        appendLog("钥匙配对", true, null, 0)
                        return@launch
                    }
                }
                pairingPhase.value = PairingPhase.FAILED
                pairingAck.value = "2 分钟内没有检测到配对完成。常见原因：没刷卡、车机上没点确认，或确认时选了非车主权限。"
            } catch (e: Exception) {
                pairingPhase.value = PairingPhase.FAILED
                pairingAck.value = e.message ?: e.javaClass.simpleName
                appendLog("发送配对请求", false, e.message, 0)
            }
        }
    }

    /**
     * 这颗按钮不许被禁用，所以链路没通时把这次点击理解成「先连上再发」。
     * CONNECTING 时只在旁边等，绝不重入 connect()：那会 bump linkGeneration，把正在跑的建链打断。
     */
    private suspend fun awaitLinkForPairing() {
        if (c.connection.state.value == VehicleConnection.State.CONNECTED) return
        if (vin.value.length != 17) throw IllegalStateException("还没填 17 位 VIN：先填 VIN 再点")
        if (c.connection.state.value == VehicleConnection.State.DISCONNECTED) connect()
        val up = withTimeoutOrNull(25_000) {
            c.connection.state.first { it == VehicleConnection.State.CONNECTED }
        } != null
        if (!up) {
            throw IllegalStateException(
                "车辆链路 25 秒内没通：车可能休眠、不在范围，或被官方 App 占着连接。到车旁再点一次。"
            )
        }
    }

    fun resetPairing() {
        pairingPhase.value = PairingPhase.IDLE
        pairingAck.value = null
    }

    /**
     * Replaces the local key pair on explicit user request. The vehicle must be re-paired after
     * this, and the stale entry deleted on the car — which is exactly why it isn't automatic.
     */
    /**
     * Explicitly replaces the local key pair. Only ever called from user action (设置页)：the
     * vehicle must be re-paired afterwards. Throws when the key store refuses, so the caller can
     * surface it instead of pretending the rotation happened.
     */
    suspend fun rotateKey() {
        c.connection.disconnect()
        val pub = c.keyManager.rotateKey()
        // 换钥匙 = 必须重新配对，引导状态清零。
        keyPairedEver.value = false
        viewModelScope.launch { c.prefs.putString("key_paired_ever", "0") }
        // Cached sessions were derived from the old key's ECDH secret; keeping them would
        // make the next connect briefly "ready" against a key the vehicle has never seen.
        c.sessionCache.clear()
        loadPublicKey()
        appendLog(
            "已重新生成钥匙",
            true,
            "指纹 ${com.teslalink.app.debug.DebugLog.fp(pub)} · 会话缓存已清空 · 请重新刷卡配对，并在车机钥匙列表删除旧条目",
            0,
        )
    }

    // ---- Diagnostics -------------------------------------------------------

    val selfTestRunning = MutableStateFlow(false)

    /** Runs the full link diagnosis once; each step lands in the log. */
    fun runSelfTest() {
        viewModelScope.launch {
            if (selfTestRunning.value) return@launch
            selfTestRunning.value = true
            // The self-test measures each stage; background polling must not be in the way,
            // otherwise a step's duration includes the queue in front of it.
            c.connection.exclusiveAction {
                try {
                    appendLog("链路自检开始", c.connection.state.value == VehicleConnection.State.CONNECTED, null, 0)
                    val lines = c.connection.selfTest()
                    lines.forEach { appendLog("自检", !it.contains("失败"), it, 0) }
                } catch (e: Exception) {
                    appendLog("链路自检", false, e.message, 0)
                } finally {
                    selfTestRunning.value = false
                }
            }
        }
    }

    // ---- Commands ----------------------------------------------------------

    fun lock() = command("落锁") { c.connection.lock() }
    fun unlock() = command("解锁") { c.connection.unlock() }
    fun trunk() = command("开启后备箱") { c.connection.actuateTrunk() }
    fun trunkClose() = command("关闭后备箱") { c.connection.closeTrunk() }
    fun frunk() = command("开启前备箱") { c.connection.openFrunk() }
    fun wake() = command("唤醒") { c.connection.wakeCommand() }
    // manual 由界面按当前读到的模式传：开空调时把他原来的 AUTO/手动 状态一并回发，别顶掉他的手档。
    fun climateOn(manual: Boolean = false) = command(if (manual) "空调开（手动）" else "空调开（AUTO）") {
        c.connection.climateOn(manual)
    }

    fun climateOff() = command("空调关") { c.connection.climateOff() }
    fun setHvacMode(manual: Boolean) = command(if (manual) "切手动风量" else "切回 AUTO") {
        c.connection.setHvacMode(manual)
    }
    fun setClimateTemp(celsius: Float) = command("设置温度 ${"%.1f".format(celsius)}°C") {
        c.connection.setClimateTemp(celsius)
    }
    fun chargeStart() = command("开始充电") { c.connection.chargeStart() }
    fun chargeStop() = command("停止充电") { c.connection.chargeStop() }
    fun chargePortOpen() = command("充电口开") { c.connection.chargePortOpen() }
    fun chargePortClose() = command("充电口关") { c.connection.chargePortClose() }
    fun setChargeLimit(percent: Int) = command("充电上限 $percent%") {
        c.connection.setChargeLimit(percent)
    }
    fun ventWindows() = command("车窗通风") { c.connection.ventWindows() }
    fun closeWindows() = command("车窗关闭") { c.connection.closeWindows() }
    fun honk() = command("鸣笛") { c.connection.honkHorn() }
    fun flash() = command("闪灯") { c.connection.flashLights() }
    fun sentry(on: Boolean) = command(if (on) "哨兵开" else "哨兵关") {
        c.connection.setSentryMode(on)
    }
    fun seatHeat(level: Int, frontLeft: Boolean) =
        command("座椅加热 ${if (frontLeft) "左" else "右"}→${seatLevelName(level)}") {
            c.connection.seatHeater(level, frontLeft)
        }
    fun seatCool(level: Int, frontLeft: Boolean) =
        command("座椅通风 ${if (frontLeft) "左" else "右"}→${seatLevelName(level)}") {
            c.connection.seatCooler(level, frontLeft)
        }
    fun steeringWheelHeater(on: Boolean) =
        command(if (on) "方向盘加热开" else "方向盘加热关") { c.connection.steeringWheelHeater(on) }
    fun maxDefrost(on: Boolean) =
        command(if (on) "MAX 除霜/预热开" else "MAX 除霜/预热关") { c.connection.maxDefrost(on) }
    fun climateKeeper(mode: String) = command("驻车模式→$mode") { c.connection.climateKeeper(mode) }
    fun bioweaponMode(on: Boolean) =
        command(if (on) "生物防御模式开" else "生物防御模式关") { c.connection.bioweaponMode(on) }

    private fun seatLevelName(level: Int): String = when (level) {
        0 -> "关"; 1 -> "低"; 2 -> "中"; else -> "高"
    }
}
