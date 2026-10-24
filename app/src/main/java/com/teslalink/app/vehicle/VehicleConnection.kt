package com.teslalink.app.vehicle

import android.content.Context
import com.google.protobuf.ByteString
import com.tesla.generated.carserver.server.CarServer
import com.tesla.generated.carserver.vehicle.Vehicle
import com.tesla.generated.keys.Keys
import com.tesla.generated.universalmessage.UniversalMessage
import com.tesla.generated.vcsec.Vcsec
import com.teslalink.app.ble.BleTransport
import com.teslalink.app.protocol.Dispatcher
import com.teslalink.app.protocol.TeslaCrypto
import com.teslalink.app.protocol.TeslaError
import com.teslalink.app.protocol.protocolErrorOf
import com.teslalink.app.protocol.shouldRetry
import com.teslalink.app.store.KeyManager
import com.teslalink.app.store.SessionCacheStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * High-level vehicle connection over BLE: sessions, commands, pairing, status.
 * Ports the orchestration in pkg/vehicle/vehicle.go + pkg/connector/ble.
 */
class VehicleConnection(
    private val context: Context,
    private val scope: CoroutineScope,
    private val keyManager: KeyManager,
    private val cacheStore: SessionCacheStore,
    /**
     * keep-alive 门控：熄屏/后台不再对车发探测——**但 D/R 行驶中例外**（手机就是仪表，
     * 后台也得保持监听）。由容器注入"前台 || 行驶闩有效"的判定，默认恒允许（测试用）。
     */
    private val keepAliveAllowed: () -> Boolean = { true },
) {
    enum class State { DISCONNECTED, CONNECTING, CONNECTED }

    private val _state = MutableStateFlow(State.DISCONNECTED)
    val state: StateFlow<State> get() = _state

    /** False when connected but the vehicle doesn't know our public key yet. */
    private val _keyPaired = MutableStateFlow(false)
    val keyPaired: StateFlow<Boolean> get() = _keyPaired

    /** Whether CarServer commands (climate/charge/telemetry) can reach the head unit. */
    private val _mcuSessionReady = MutableStateFlow(false)
    val mcuSessionReady: StateFlow<Boolean> get() = _mcuSessionReady

    /**
     * True while a manual command or the diagnostic run owns the link. BLE here is a single
     * request-response pipe: if background polling keeps queueing during a command, the measured
     * latency is really the queue depth, and the self-test's per-step timings become meaningless.
     * Pollers read this and stand down.
     */
    private val _exclusive = MutableStateFlow(false)
    val exclusive: StateFlow<Boolean> get() = _exclusive

    @Volatile private var exclusiveDepth = 0
    private val exclusiveLock = Any()

    /**
     * Run a user-initiated action with the link reserved for it alone. Reference-counted so a
     * command issued inside the self-test doesn't release the self-test's claim early (which would
     * let the 250 ms lanes back in and turn every remaining step time into queue depth).
     */
    suspend fun <T> exclusiveAction(block: suspend () -> T): T {
        synchronized(exclusiveLock) {
            exclusiveDepth++
            _exclusive.value = true
        }
        try {
            return block()
        } finally {
            synchronized(exclusiveLock) {
                exclusiveDepth--
                if (exclusiveDepth == 0) _exclusive.value = false
            }
        }
    }

    private var vin: String = ""
    private var ble: BleTransport? = null
    private var dispatcher: Dispatcher? = null
    private var linkWatchJob: kotlinx.coroutines.Job? = null

    private val vcsecMutex = Mutex()
    private val infotainmentMutex = Mutex()

    /**
     * Handshakes are serialized: the connect-time infotainment knock, the pairing poll and any
     * command can otherwise run two concurrent SessionInfo exchanges, which makes each step's
     * measured duration meaningless and can leave a session half-negotiated.
     * Lock order is always infotainmentMutex -> sessionMutex -> vcsecMutex (never the reverse).
     */
    private val sessionMutex = Mutex()

    /** The dashboard swallows polling errors, so report the first CarServer failure per link. */
    @Volatile private var mcuErrorReported = false

    /** Likewise: report only the first inconclusive pairing check per link. */
    @Volatile private var pairCheckAnomalyReported = false

    /**
     * Bumped by every connect/disconnect. A connect that was abandoned midway (user pressed
     * 断开, or the VIN was switched) must not resume and declare itself CONNECTED on a dead GATT.
     */
    @Volatile private var linkGeneration = 0

    suspend fun connect(targetVin: String, timeoutMs: Long = 20_000): Boolean {
        disconnect()
        val generation = ++linkGeneration
        vin = targetVin
        mcuErrorReported = false
        pairCheckAnomalyReported = false
        _state.value = State.CONNECTING
        // A failed key restore must not leave the UI stuck on "connecting".
        val keyPair = try {
            keyManager.getOrCreateKeyPair()
        } catch (e: Exception) {
            _state.value = State.DISCONNECTED
            throw e
        }
        val publicBytes = keyManager.publicBytes()
        val transport = BleTransport(context, scope)
        val dispatcher = Dispatcher(targetVin, keyPair.private, publicBytes, transport)
        this.ble = transport
        this.dispatcher = dispatcher

        try {
            val prefs = com.teslalink.app.store.PrefsStore(context)
            val saved = prefs.getString("ble_direct_addr")
            var connectedAddr: String? = saved
            if (saved != null) {
                com.teslalink.app.debug.DebugLog.log("vehicle", "使用已记录设备地址 $saved 直连（跳过扫描）")
                try {
                    transport.connectDevice(transport.deviceFromAddress(saved), gattTimeoutMs = timeoutMs)
                } catch (e: Exception) {
                    // The car may have rotated its address: fall back to scanning by name.
                    com.teslalink.app.debug.DebugLog.error(
                        "vehicle",
                        "记住的地址直连失败，回退到按名称扫描: ${e.message}"
                    )
                    connectedAddr = transport.connect(
                        targetVin, timeoutMs, prefs.getString("ble_name_override")
                    ).address
                }
            } else {
                connectedAddr = transport.connect(
                    targetVin, timeoutMs, prefs.getString("ble_name_override")
                ).address
            }
            // Remember the address that worked so the next connect skips discovery.
            connectedAddr?.let { prefs.putString("ble_direct_addr", it) }
        } catch (e: Exception) {
            _state.value = State.DISCONNECTED
            throw e
        }
        if (generation != linkGeneration) {
            // Superseded by a newer connect/disconnect while GATT was coming up.
            transport.disconnect()
            throw TeslaError.NotConnected()
        }
        dispatcher.start(scope)
        watchLink(transport, dispatcher)

        // Optimistically load cached sessions; invalid caches recover automatically.
        val cached = cacheStore.load(targetVin)
        if (cached.isNotEmpty()) {
            try {
                dispatcher.loadCache(cached)
            } catch (e: Exception) {
                // ignore, handshake will rebuild
            }
        }

        // Always establish VCSEC first: it works even when infotainment is asleep.
        com.teslalink.app.debug.DebugLog.log("vehicle", "GATT 就绪，开始 VCSEC 握手 vin=$targetVin")
        val started = System.currentTimeMillis()
        try {
            val handshaked = ensureSession(UniversalMessage.Domain.DOMAIN_VEHICLE_SECURITY)
            _keyPaired.value = true
            com.teslalink.app.debug.DebugLog.log(
                "vehicle",
                if (handshaked) "握手完成（${System.currentTimeMillis() - started}ms）"
                else "会话来自缓存（${System.currentTimeMillis() - started}ms），开始校验"
            )
            if (!handshaked) verifyCachedVcsecSession()
        } catch (e: TeslaError.KeyNotPaired) {
            // Expected before first pairing: the radio chain is fine, the car just
            // doesn't know our public key yet. Stay connected so the user can pair.
            _keyPaired.value = false
            com.teslalink.app.debug.DebugLog.log("vehicle", "已连接，但公钥未配对（等待配对流程）")
        } catch (e: Exception) {
            // GATT works but the handshake failed (busy / refused / malformed reply).
            // Stay connected anyway: the pairing request only needs the raw VCSEC
            // channel, and the log tells us what the vehicle actually said.
            _keyPaired.value = false
            com.teslalink.app.debug.DebugLog.error("vehicle", "握手未完成: ${e.message}")
        }
        if (generation != linkGeneration) {
            com.teslalink.app.debug.DebugLog.error("vehicle", "连接过程已被取消，不标记为已连接")
            transport.disconnect()
            return false
        }
        _state.value = State.CONNECTED
        startKeepAlive(transport)
        // Always audit the vehicle's keychain (unauthenticated): this is the only way to tell
        // "the car doesn't have this key" apart from "our session is stale".
        auditKeychain()
        if (_keyPaired.value) {
            // The official library handshakes BOTH domains at connect (StartSession(nil)).
            // Do the same off the main path so the head unit gets knocked awake instead of
            // waiting for the first command — and log whichever way it goes.
            scope.launch {
                val t0 = System.currentTimeMillis()
                try {
                    ensureSession(UniversalMessage.Domain.DOMAIN_INFOTAINMENT)
                    com.teslalink.app.debug.DebugLog.log(
                        "vehicle",
                        "车机域会话已建立（${System.currentTimeMillis() - t0}ms）"
                    )
                } catch (e: Exception) {
                    com.teslalink.app.debug.DebugLog.error(
                        "vehicle",
                        "车机域会话未建立（${System.currentTimeMillis() - t0}ms）：${e.message}"
                    )
                }
            }
        }
        com.teslalink.app.debug.DebugLog.log("vehicle", "连接完成 vin=$targetVin")
        return true
    }

    /**
     * Print the vehicle's own view of us, WITHOUT authentication: the keychain list comes back even
     * when every authenticated command fails, so this distinguishes "the car forgot my key" from
     * "my session/permissions are wrong". Matches our public key by SHA-1 (over both the 65-byte
     * uncompressed point and the bare 64-byte X||Y, since the vehicle's exact input isn't documented).
     */
    private suspend fun auditKeychain() {
        val log = com.teslalink.app.debug.DebugLog
        val pub = keyManager.publicBytes()
        val sha65 = TeslaCrypto.sha1(pub)
        val sha64 = TeslaCrypto.sha1(pub.copyOfRange(1, pub.size))
        log.log(
            "keychain",
            "本机公钥 指纹=${log.fp(pub)} SHA1(65B)=${log.hex(sha65, 20)}… SHA1(64B)=${log.hex(sha64, 20)}…"
        )
        val info = try {
            val reply = executeVCSEC(
                Payloads.informationRequest(
                    Vcsec.InformationRequestType.INFORMATION_REQUEST_TYPE_GET_WHITELIST_INFO
                ),
                Dispatcher.Auth.NONE,
                whitelistOperation = false,
                deadlineMs = 6_000,
            )
            if (reply.hasWhitelistInfo()) reply.whitelistInfo else null
        } catch (e: Exception) {
            log.error("keychain", "免鉴权钥匙链查询失败：${e.message}")
            null
        } ?: return

        val entries = info.whitelistEntriesList
        log.log(
            "keychain",
            "车辆钥匙链：${info.numberOfEntries} 把，槽位掩码 0x${java.lang.Integer.toHexString(info.slotMask)}，" +
                "回传条目 ${entries.size} 个"
        )
        entries.forEachIndexed { i, entry ->
            val sha = entry.publicKeySHA1.toByteArray()
            val mine = when {
                sha.size == sha65.size && sha.contentEquals(sha65) -> " ← 就是我们这把（65B 哈希）"
                sha.size == sha64.size && sha.contentEquals(sha64) -> " ← 就是我们这把（64B 哈希）"
                else -> ""
            }
            log.log("keychain", "  钥匙 ${i + 1}: ${log.hex(sha, 20)}$mine")
        }
        val enrolled = entries.any { entry ->
            val sha = entry.publicKeySHA1.toByteArray()
            (sha.size == sha65.size && sha.contentEquals(sha65)) ||
                (sha.size == sha64.size && sha.contentEquals(sha64))
        }
        if (enrolled) {
            log.log("keychain", "结论：车辆钥匙链里有这把公钥 → 之后的失败应归因于会话/车机同步")
        } else {
            log.error(
                "keychain",
                "结论：车辆钥匙链里没有这把公钥 → 本机钥匙与车辆记录不一致（旧版静默换钥匙的后果，或车机侧已删除），需要重新配对"
            )
        }
    }

    /**
     * A cached session can be stale (vehicle restarted its epoch) or can predate pairing —
     * in both cases every authenticated command comes back as "unknown key". Prove the
     * session with one cheap authenticated read, and re-handshake if the vehicle objects.
     */
    private suspend fun verifyCachedVcsecSession() {
        val d = dispatcher ?: return
        // GET_STATUS authenticated: proves the session's key/counter/AAD are accepted, without
        // needing any privilege beyond having a valid session at all.
        val probe = Payloads.informationRequest(
            Vcsec.InformationRequestType.INFORMATION_REQUEST_TYPE_GET_STATUS
        )
        try {
            executeVCSEC(probe, Dispatcher.Auth.GCM, whitelistOperation = false, deadlineMs = 4_000)
            com.teslalink.app.debug.DebugLog.log("vehicle", "缓存会话校验通过")
        } catch (e: Exception) {
            com.teslalink.app.debug.DebugLog.error(
                "vehicle",
                "缓存会话不可用（${e.message}），作废并重新握手"
            )
            d.invalidateSession(UniversalMessage.Domain.DOMAIN_VEHICLE_SECURITY)
            d.invalidateSession(UniversalMessage.Domain.DOMAIN_INFOTAINMENT)
            _keyPaired.value = false
            syncSessionFlags()
            try {
                if (d.startSession(UniversalMessage.Domain.DOMAIN_VEHICLE_SECURITY, deadlineMs = 10_000)) {
                    saveSessions()
                }
                _keyPaired.value = true
                com.teslalink.app.debug.DebugLog.log("vehicle", "重新握手成功")
            } catch (e2: TeslaError.KeyNotPaired) {
                _keyPaired.value = false
                com.teslalink.app.debug.DebugLog.error("vehicle", "车辆不认这把公钥：需要重新配对")
            } catch (e2: Exception) {
                _keyPaired.value = false
                com.teslalink.app.debug.DebugLog.error("vehicle", "重新握手失败: ${e2.message}")
            }
            syncSessionFlags()
        }
    }

    fun disconnect() {
        linkGeneration++
        linkWatchJob?.cancel()
        linkWatchJob = null
        keepAliveJob?.cancel()
        keepAliveJob = null
        dispatcher?.stop()
        ble?.disconnect()
        ble = null
        dispatcher = null
        _state.value = State.DISCONNECTED
        _keyPaired.value = false
        _mcuSessionReady.value = false
    }

    /**
     * 连接保活：车辆与系统都可能闲置掉线（官方 App 的钥匙就是常年保持在线的）。
     * 每 25 秒一次免鉴权 GET_STATUS，既不让链路闲置，也让控制页的门锁状态保持新鲜；
     * 任何功能占用链路（命令/自检）时自动让位。
     * **门控**：熄屏/退后台停止探测，夜里锁屏停在仪表页不再整夜戳车；
     * D/R 行驶中例外（keepAliveAllowed 注入了行驶闩），后台照样保持监听。
     */
    private fun startKeepAlive(transport: BleTransport) {
        keepAliveJob?.cancel()
        keepAliveJob = scope.launch {
            val probe = Payloads.informationRequest(
                Vcsec.InformationRequestType.INFORMATION_REQUEST_TYPE_GET_STATUS
            )
            var silentFails = 0
            while (ble === transport && _state.value == State.CONNECTED) {
                delay(25_000)
                if (ble !== transport || _state.value != State.CONNECTED) break
                if (_exclusive.value) continue
                if (!keepAliveAllowed()) continue
                runCatching {
                    sendAndAwait(
                        UniversalMessage.RoutableMessage.newBuilder()
                            .setToDestination(
                                UniversalMessage.Destination.newBuilder()
                                    .setDomain(UniversalMessage.Domain.DOMAIN_VEHICLE_SECURITY)
                            )
                            .setProtobufMessageAsBytes(ByteString.copyFrom(probe.toByteArray())),
                        Dispatcher.Auth.NONE,
                        timeoutMs = 4_000,
                    )
                    silentFails = 0
                }.onFailure {
                    // 半死链路（GATT 在、车不应答）以前完全静默；记日志但别刷屏——
                    // 首次与每第 8 次各记一行，够定位又不淹没日志环。
                    silentFails++
                    if (silentFails == 1 || silentFails % 8 == 0) {
                        com.teslalink.app.debug.DebugLog.error(
                            "vehicle", "keep-alive 未应答（连续 $silentFails 次）：${it.message ?: it.javaClass.simpleName}",
                        )
                    }
                }
            }
        }
    }

    private var keepAliveJob: kotlinx.coroutines.Job? = null

    /**
     * Propagate a dropped GATT link into our own state. Without this the page keeps showing
     * 已连接 while every command silently times out, and the pairing poll spins on a dead link.
     */
    private fun watchLink(transport: BleTransport, owner: Dispatcher) {
        linkWatchJob?.cancel()
        linkWatchJob = scope.launch {
            transport.state.collect { state ->
                if (state != BleTransport.ConnectionState.DISCONNECTED) return@collect
                if (ble !== transport || dispatcher !== owner) return@collect
                if (_state.value == State.DISCONNECTED) return@collect
                com.teslalink.app.debug.DebugLog.error(
                    "vehicle",
                    "BLE 链路已断开（车机休眠/超出范围/连接被挤占）：需要重新连接"
                )
                _state.value = State.DISCONNECTED
                _keyPaired.value = false
                _mcuSessionReady.value = false
            }
        }
    }

    private suspend fun saveSessions() {
        dispatcher?.let { cacheStore.save(vin, it.exportCache()) }
    }

    /**
     * Establish a protocol session with a domain, waking the vehicle if needed.
     * Returns true when a real handshake took place (false = existing/cached session reused).
     * @param allowWake false = 车机不应答就立刻失败，不发 RKE 唤醒、不走 30s 重试
     *   （遥测快速读专用，防止车速道被一次失败的握手冻住几十秒）。
     */
    suspend fun ensureSession(domain: UniversalMessage.Domain, allowWake: Boolean = true): Boolean {
        val d = dispatcher ?: throw TeslaError.NotConnected()
        return try {
            sessionMutex.withLock { ensureSessionInner(d, domain, allowWake) }
        } finally {
            syncSessionFlags()
        }
    }

    private suspend fun ensureSessionInner(
        d: Dispatcher,
        domain: UniversalMessage.Domain,
        allowWake: Boolean = true,
    ): Boolean {
        var firstError: Exception? = null
        try {
            // Session state only changes on a handshake — persist the cache then,
            // not on every command (the dashboard polls several times a second).
            val handshaked = d.startSession(domain, deadlineMs = 8_000)
            if (handshaked) saveSessions()
            return handshaked
        } catch (e: Exception) {
            if (domain != UniversalMessage.Domain.DOMAIN_INFOTAINMENT) throw e
            if (!allowWake) throw e
            firstError = e
        }
        // Infotainment is probably asleep: poke VCSEC (which stays awake) then retry.
        // Unknown-key errors are included — a sleeping MCU cannot answer at all, so its
        // reply is indistinguishable from "key not synced yet" until it is awake.
        wake()
        try {
            val handshaked = d.startSession(domain, deadlineMs = 30_000)
            if (handshaked) saveSessions()
            return handshaked
        } catch (e: Exception) {
            val original = firstError
            if (original != null && e.message != original.message) {
                throw TeslaError.Nominal(
                    "车机域不可达：${original.message}；唤醒后重试：${e.message}"
                )
            }
            throw e
        }
    }

    private fun syncSessionFlags() {
        _mcuSessionReady.value =
            dispatcher?.isSessionReady(UniversalMessage.Domain.DOMAIN_INFOTAINMENT) == true
    }

    // ---- Low-level send helpers --------------------------------------------

    private suspend fun sendAndAwait(
        builder: UniversalMessage.RoutableMessage.Builder,
        auth: Dispatcher.Auth,
        timeoutMs: Long,
    ): UniversalMessage.RoutableMessage {
        val d = dispatcher ?: throw TeslaError.NotConnected()
        val handler = d.send(builder, auth)
        try {
            return withTimeoutOrNull(timeoutMs) { handler.channel.receive() }
                ?: throw TeslaError.Timeout(mayHaveSucceeded = false)
        } finally {
            d.closeHandler(handler)
        }
    }

    /**
     * Send a VCSEC command, retransmitting while the vehicle reports WAIT/BUSY.
     * Returns the terminal FromVCSECMessage.
     */
    private suspend fun executeVCSEC(
        payload: Vcsec.UnsignedMessage,
        auth: Dispatcher.Auth,
        whitelistOperation: Boolean,
        deadlineMs: Long = 20_000,
    ): Vcsec.FromVCSECMessage = vcsecMutex.withLock {
        val deadline = System.currentTimeMillis() + deadlineMs
        var lastError: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                val message = UniversalMessage.RoutableMessage.newBuilder()
                    .setToDestination(
                        UniversalMessage.Destination.newBuilder()
                            .setDomain(UniversalMessage.Domain.DOMAIN_VEHICLE_SECURITY)
                    )
                    .setProtobufMessageAsBytes(ByteString.copyFrom(payload.toByteArray()))
                    // Only ask for an encrypted response when we actually hold a session to
                    // decrypt it with: an unauthenticated read whose reply comes back encrypted
                    // has no decryption context and would be silently dropped (looks like a
                    // timeout, which is exactly what the keychain audit must never do).
                    .setFlags(if (auth == Dispatcher.Auth.NONE) 0 else DEFAULT_FLAGS)
                val reply = sendAndAwait(message, auth, timeoutMs = 5_000)
                val protocolErr = protocolErrorOf(reply)
                if (protocolErr != null) {
                    lastError = protocolErr
                    if (!shouldRetry(protocolErr)) throw protocolErr
                } else {
                    val fromVCSEC = Vcsec.FromVCSECMessage.parseFrom(reply.protobufMessageAsBytes)
                    // The vehicle attaches NominalError{GENERICERROR_NONE} to perfectly successful
                    // informational replies (whitelist info, key entries, GET_STATUS, wake acks).
                    // Go's readUntil never inspects it for those paths; treating NONE as a failure
                    // here rejected valid data and forced a needless re-handshake on every connect.
                    fromVCSEC.nominalError
                        ?.takeIf {
                            it.genericError !=
                                com.tesla.generated.errors.Errors.GenericError_E.GENERICERROR_NONE
                        }
                        ?.let { nominal ->
                            throw TeslaError.Nominal("VCSEC: ${nominal.genericError.name}")
                        }
                    val status = if (fromVCSEC.hasCommandStatus()) fromVCSEC.commandStatus else null
                    if (status == null) {
                        // Empty message = success for non-whitelist operations.
                        return@withLock fromVCSEC
                    }
                    if (whitelistOperation && status.hasWhitelistOperationStatus()) {
                        val info = status.whitelistOperationStatus.whitelistOperationInformation
                        if (info == Vcsec.WhitelistOperation_information_E.WHITELISTOPERATION_INFORMATION_NONE) {
                            return@withLock fromVCSEC
                        }
                        throw TeslaError.Keychain(info.name)
                    }
                    when (status.operationStatus) {
                        Vcsec.OperationStatus_E.OPERATIONSTATUS_WAIT,
                        Vcsec.OperationStatus_E.OPERATIONSTATUS_ERROR,
                        -> {
                            // Vehicle busy / legacy error marker: discard and retry.
                            lastError = TeslaError.Busy()
                        }
                        else -> return@withLock fromVCSEC
                    }
                }
            } catch (e: TeslaError) {
                lastError = e
                if (!shouldRetry(e)) throw e
            } catch (e: Exception) {
                // Transport or timeout errors: retry until deadline.
                lastError = e
            }
            kotlinx.coroutines.delay(RETRY_INTERVAL_MS)
        }
        throw lastError ?: TeslaError.Timeout(mayHaveSucceeded = true)
    }

    /** Send a CarServer action to infotainment and parse the response. */
    /**
     * attempts/timeout 可以收紧给遥测快速读用：infotainmentMutex 串行所有车机域对话，
     * 一次 15s×3 的失败等待会把车速道堵死十几秒（他实测"速度更新延迟太大"的根源之一）。
     * DRIVE 读失败不心疼——250ms 后下一轮再来。
     */
    /**
     * @param allowWake false = 会话建不起来就直接失败，**不许发 RKE 唤醒**。
     *   给遥测快速读（车速道）用：车机睡着时"唤醒+30s 重试握手"要持 infotainmentMutex
     *   半分多钟，等于把 250ms 的车速读冻死——车速新鲜度压倒一切，宁可这一轮失败
     *   250ms 后再来。需要真唤醒的场景（控制命令、慢车道探测）保持 true。
     */
    private suspend fun executeCarServer(
        action: CarServer.Action,
        attempts: Int = 3,
        timeoutMs: Long = 15_000,
        allowWake: Boolean = true,
    ): CarServer.Response =
        infotainmentMutex.withLock {
            var tried = 0
            var lastError: Throwable? = null
            while (tried < attempts) {
                tried++
                try {
                    ensureSession(UniversalMessage.Domain.DOMAIN_INFOTAINMENT, allowWake = allowWake)
                    val message = UniversalMessage.RoutableMessage.newBuilder()
                        .setToDestination(
                            UniversalMessage.Destination.newBuilder()
                                .setDomain(UniversalMessage.Domain.DOMAIN_INFOTAINMENT)
                        )
                        .setProtobufMessageAsBytes(ByteString.copyFrom(action.toByteArray()))
                        .setFlags(DEFAULT_FLAGS)
                    val reply = sendAndAwait(message, Dispatcher.Auth.GCM, timeoutMs = timeoutMs)
                    protocolErrorOf(reply)?.let { throw it }
                    val response = CarServer.Response.parseFrom(reply.protobufMessageAsBytes)
                    if (response.actionStatus.result == CarServer.OperationStatus_E.OPERATIONSTATUS_ERROR) {
                        val reason = if (response.actionStatus.hasResultReason()) {
                            response.actionStatus.resultReason.plainText.ifEmpty { "unspecified" }
                        } else "unspecified"
                        throw TeslaError.Nominal(reason)
                    }
                    return@withLock response
                } catch (e: TeslaError.KeyNotPaired) {
                    throw e
                } catch (e: TeslaError.Timeout) {
                    lastError = e
                } catch (e: TeslaError.NoSession) {
                    lastError = e
                    dispatcher?.invalidateSession(UniversalMessage.Domain.DOMAIN_INFOTAINMENT)
                    syncSessionFlags()
                } catch (e: TeslaError) {
                    lastError = e
                    if (!shouldRetry(e)) throw e
                    if (e is TeslaError.VehicleError && e.canRetryWithUpdatedSession) {
                        // Session info was already consumed by the dispatcher.
                    } else {
                        dispatcher?.invalidateSession(UniversalMessage.Domain.DOMAIN_INFOTAINMENT)
                        syncSessionFlags()
                    }
                }
            }
            // Report the head-unit failure reason once per connection: the dashboard swallows
            // exceptions, and without this the log can't tell why 车机域 doesn't answer.
            val finalError = lastError ?: TeslaError.Timeout(false)
            if (!mcuErrorReported) {
                mcuErrorReported = true
                com.teslalink.app.debug.DebugLog.error(
                    "carserver",
                    "车机指令失败（本次连接只报一次）：${finalError.message}"
                )
            }
            throw finalError
        }

    private suspend fun wake(): Unit = withSessionRecovery(UniversalMessage.Domain.DOMAIN_VEHICLE_SECURITY) {
        wakeRaw()
    }

    private suspend fun wakeRaw() {
        executeVCSEC(
            Payloads.rkeAction(Vcsec.RKEAction_E.RKE_ACTION_WAKE_VEHICLE),
            Dispatcher.Auth.GCM,
            whitelistOperation = false,
            deadlineMs = 15_000,
        )
    }

    /**
     * A session restored from the cache may be one the vehicle no longer accepts (it restarted
     * its epoch, or the entry predates pairing). Those commands fail as "unknown key": drop the
     * session, let the caller's ensureSession negotiate a real one, and retry once.
     */
    private suspend fun <T> withSessionRecovery(
        domain: UniversalMessage.Domain,
        block: suspend () -> T,
    ): T = try {
        block()
    } catch (e: TeslaError.KeyNotPaired) {
        com.teslalink.app.debug.DebugLog.error(
            "vehicle",
            "车辆拒绝该会话（${e.message}）：作废 ${domain.name} 会话后重握手重试一次"
        )
        dispatcher?.invalidateSession(domain)
        if (domain == UniversalMessage.Domain.DOMAIN_VEHICLE_SECURITY) _keyPaired.value = false
        syncSessionFlags()
        val result = block()
        // Any command the vehicle accepted proves the key really is on the whitelist.
        _keyPaired.value = true
        result
    }

    // ---- Public commands ---------------------------------------------------

    data class CommandResult(val ok: Boolean, val message: String?, val latencyMs: Long)

    private suspend fun vcsecCommand(
        whitelist: Boolean,
        payload: () -> Vcsec.UnsignedMessage,
    ): CommandResult {
        val start = System.currentTimeMillis()
        return try {
            withSessionRecovery(UniversalMessage.Domain.DOMAIN_VEHICLE_SECURITY) {
                ensureSession(UniversalMessage.Domain.DOMAIN_VEHICLE_SECURITY)
                executeVCSEC(payload(), Dispatcher.Auth.GCM, whitelistOperation = whitelist)
            }
            CommandResult(true, null, System.currentTimeMillis() - start)
        } catch (e: Exception) {
            CommandResult(false, e.message, System.currentTimeMillis() - start)
        }
    }

    private suspend fun carServerCommand(action: CarServer.Action): CommandResult {
        val start = System.currentTimeMillis()
        return try {
            withSessionRecovery(UniversalMessage.Domain.DOMAIN_INFOTAINMENT) {
                executeCarServer(action)
            }
            CommandResult(true, null, System.currentTimeMillis() - start)
        } catch (e: Exception) {
            CommandResult(false, e.message, System.currentTimeMillis() - start)
        }
    }

    suspend fun lock(): CommandResult = vcsecCommand(false) {
        Payloads.rkeAction(Vcsec.RKEAction_E.RKE_ACTION_LOCK)
    }

    suspend fun unlock(): CommandResult = vcsecCommand(false) {
        Payloads.rkeAction(Vcsec.RKEAction_E.RKE_ACTION_UNLOCK)
    }

    suspend fun actuateTrunk(): CommandResult = vcsecCommand(false) {
        Payloads.closureMove(Payloads.Closure.REAR_TRUNK, Vcsec.ClosureMoveType_E.CLOSURE_MOVE_TYPE_MOVE)
    }

    suspend fun closeTrunk(): CommandResult = vcsecCommand(false) {
        Payloads.closureMove(Payloads.Closure.REAR_TRUNK, Vcsec.ClosureMoveType_E.CLOSURE_MOVE_TYPE_CLOSE)
    }

    suspend fun openFrunk(): CommandResult = vcsecCommand(false) {
        Payloads.closureMove(Payloads.Closure.FRONT_TRUNK, Vcsec.ClosureMoveType_E.CLOSURE_MOVE_TYPE_MOVE)
    }

    suspend fun wakeCommand(): CommandResult {
        val start = System.currentTimeMillis()
        return try {
            wake()
            CommandResult(true, null, System.currentTimeMillis() - start)
        } catch (e: Exception) {
            CommandResult(false, e.message, System.currentTimeMillis() - start)
        }
    }

    suspend fun climateOn(manual: Boolean = false): CommandResult = carServerCommand(Payloads.climateOn(manual))
    suspend fun climateOff(): CommandResult = carServerCommand(Payloads.climateOff())

    /**  AUTO ↔ 手动（风量档）：同一个动作，只是 manual_override 不同，都要求空调在运行。 */
    suspend fun setHvacMode(manual: Boolean): CommandResult = carServerCommand(Payloads.climateOn(manual))
    suspend fun setClimateTemp(celsius: Float): CommandResult =
        carServerCommand(Payloads.changeClimateTemp(celsius, celsius))
    suspend fun chargeStart(): CommandResult = carServerCommand(Payloads.chargeStart())
    suspend fun chargeStop(): CommandResult = carServerCommand(Payloads.chargeStop())
    suspend fun setChargeLimit(percent: Int): CommandResult =
        carServerCommand(Payloads.changeChargeLimit(percent))
    suspend fun chargePortOpen(): CommandResult = carServerCommand(Payloads.chargePortOpen())
    suspend fun chargePortClose(): CommandResult = carServerCommand(Payloads.chargePortClose())
    suspend fun ventWindows(): CommandResult = carServerCommand(Payloads.ventWindows())
    suspend fun closeWindows(): CommandResult = carServerCommand(Payloads.closeWindows())
    suspend fun honkHorn(): CommandResult = carServerCommand(Payloads.honkHorn())
    suspend fun flashLights(): CommandResult = carServerCommand(Payloads.flashLights())
    suspend fun setSentryMode(on: Boolean): CommandResult = carServerCommand(Payloads.setSentryMode(on))
    suspend fun seatHeater(level: Int, frontLeft: Boolean): CommandResult =
        carServerCommand(Payloads.seatHeater(level, frontLeft))
    suspend fun seatCooler(level: Int, frontLeft: Boolean): CommandResult =
        carServerCommand(Payloads.seatCooler(level, frontLeft))
    suspend fun steeringWheelHeater(on: Boolean): CommandResult =
        carServerCommand(Payloads.steeringWheelHeater(on))
    suspend fun maxDefrost(on: Boolean): CommandResult = carServerCommand(Payloads.maxDefrost(on))
    suspend fun climateKeeper(mode: String): CommandResult = carServerCommand(Payloads.climateKeeper(mode))
    suspend fun bioweaponMode(on: Boolean): CommandResult = carServerCommand(Payloads.bioweaponMode(on))

    // ---- Status ------------------------------------------------------------

    data class BodyControllerState(
        val lockState: Int?,
        val sleepStatus: Int?,
        val userPresence: Int?,
        val closures: Vcsec.ClosureStatuses?,
    )

    /**
     * Read lock/closure/sleep state from VCSEC. Works over BLE without waking
     * infotainment and without an authenticated session.
     */
    suspend fun bodyControllerState(): BodyControllerState? = vcsecMutex.withLock {
        val payload = Payloads.informationRequest(
            Vcsec.InformationRequestType.INFORMATION_REQUEST_TYPE_GET_STATUS
        )
        val message = UniversalMessage.RoutableMessage.newBuilder()
            .setToDestination(
                UniversalMessage.Destination.newBuilder()
                    .setDomain(UniversalMessage.Domain.DOMAIN_VEHICLE_SECURITY)
            )
            .setProtobufMessageAsBytes(ByteString.copyFrom(payload.toByteArray()))
        val reply = sendAndAwait(message, Dispatcher.Auth.NONE, timeoutMs = 5_000)
        protocolErrorOf(reply)?.let { throw it }
        val fromVCSEC = Vcsec.FromVCSECMessage.parseFrom(reply.protobufMessageAsBytes)
        val status = fromVCSEC.vehicleStatus ?: return null
        BodyControllerState(
            lockState = status.vehicleLockState.number,
            sleepStatus = status.vehicleSleepStatus.number.takeIf { it != 0 },
            userPresence = status.userPresence.number.takeIf { it != 0 },
            closures = if (status.hasClosureStatuses()) status.closureStatuses else null,
        )
    }

    /** Read vehicle data over BLE (wakes infotainment). */
    suspend fun vehicleData(
        categories: List<Payloads.Category>,
        attempts: Int = 3,
        timeoutMs: Long = 15_000,
        wake: Boolean = true,
    ): Vehicle.VehicleData =
        withSessionRecovery(UniversalMessage.Domain.DOMAIN_INFOTAINMENT) {
            executeCarServer(
                Payloads.getVehicleData(categories), attempts, timeoutMs,
                allowWake = wake,
            ).vehicleData
        }

    // ---- Key pairing -------------------------------------------------------

    /**
     * Send the add-key request over BLE. The user must tap an NFC card/keyfob
     * on the center console and confirm on the vehicle screen. Returns the vehicle's
     * acknowledgement, or null when the vehicle stayed silent.
     */
    suspend fun sendAddKeyRequest(): String? {
        val publicBytes = keyManager.publicBytes()
        val envelope = Payloads.addKeyRequestEnvelope(publicBytes, Keys.Role.ROLE_OWNER)
        val transport = ble ?: throw TeslaError.NotConnected()

        com.teslalink.app.debug.DebugLog.log(
            "vehicle",
            "发送配对请求：裸 ToVCSECMessage(present-key), 角色=OWNER, 形式=ANDROID_DEVICE, " +
                "公钥=${com.teslalink.app.debug.DebugLog.hex(publicBytes, 8)}…"
        )

        // The reply is a raw FromVCSECMessage (not a RoutableMessage), so the dispatcher ignores
        // it. Listen synchronously on the transport — a coroutine collector on the SharedFlow can
        // subscribe after the vehicle already answered, and the ack would be lost.
        val ack = kotlinx.coroutines.CompletableDeferred<String>()
        val detach = transport.addFrameListener { bytes ->
            val described = describeVCSECReply(bytes)
            if (described != null && !ack.isCompleted) ack.complete(described)
        }
        val reply: String?
        try {
            transport.send(envelope.toByteArray())
            reply = kotlinx.coroutines.withTimeoutOrNull(8_000) { ack.await() }
            com.teslalink.app.debug.DebugLog.log(
                "vehicle",
                if (reply == null) "配对请求已发出，8 秒内无回执（正常：请先刷卡）"
                else "配对请求回执：$reply"
            )
        } finally {
            detach()
        }
        return reply
    }

    /** 把钥匙链操作回执翻译成人能读懂的一句话（vcsec.proto 的 WhitelistOperation_information_E）。 */
    private fun glossWhitelistInfo(
        info: Vcsec.WhitelistOperation_information_E,
    ): String = when (info) {
        Vcsec.WhitelistOperation_information_E.WHITELISTOPERATION_INFORMATION_NONE ->
            "车辆已受理，等待刷卡与车机确认"
        Vcsec.WhitelistOperation_information_E.WHITELISTOPERATION_INFORMATION_PUBLIC_KEY_NOT_ON_WHITELIST ->
            "这把公钥不在钥匙链里"
        Vcsec.WhitelistOperation_information_E.WHITELISTOPERATION_INFORMATION_ATTEMPTING_TO_ADD_KEY_THAT_IS_ALREADY_ON_THE_WHITELIST ->
            "这把公钥已经在钥匙链里了（重复配对）"
        Vcsec.WhitelistOperation_information_E.WHITELISTOPERATION_INFORMATION_NOT_ALLOWED_TO_ADD_UNLESS_ON_READER ->
            "需要先把 NFC 卡贴到读取区"
        Vcsec.WhitelistOperation_information_E.WHITELISTOPERATION_INFORMATION_LOCAL_ENTITY_AUTH_FAILED_UI_DENIED ->
            "车机上点了拒绝"
        Vcsec.WhitelistOperation_information_E.WHITELISTOPERATION_INFORMATION_LOCAL_ENTITY_AUTH_FAILED_TIMED_OUT_WAITING_FOR_TAP ->
            "超时：没有刷卡"
        Vcsec.WhitelistOperation_information_E.WHITELISTOPERATION_INFORMATION_LOCAL_ENTITY_AUTH_FAILED_TIMED_OUT_WAITING_FOR_UI_ACK ->
            "超时：刷了卡但没在车机点确认"
        Vcsec.WhitelistOperation_information_E.WHITELISTOPERATION_INFORMATION_LOCAL_ENTITY_AUTH_FAILED_VALET_MODE ->
            "代客模式限制，无法添加钥匙"
        Vcsec.WhitelistOperation_information_E.WHITELISTOPERATION_INFORMATION_LOCAL_ENTITY_AUTH_FAILED_CANCELLED ->
            "车机侧取消了本次认证"
        Vcsec.WhitelistOperation_information_E.WHITELISTOPERATION_INFORMATION_COULD_NOT_START_LOCAL_ENTITY_AUTH ->
            "车机没能弹出确认框"
        Vcsec.WhitelistOperation_information_E.WHITELISTOPERATION_INFORMATION_WHITELIST_FULL,
        Vcsec.WhitelistOperation_information_E.WHITELISTOPERATION_INFORMATION_KEYFOB_SLOTS_FULL,
        ->
            "钥匙链已满，需要先删掉一把"
        Vcsec.WhitelistOperation_information_E.WHITELISTOPERATION_INFORMATION_NO_PERMISSION_TO_ADD ->
            "当前钥匙没有添加新钥匙的权限"
        else -> "回执码 ${info.number}"
    }

    /** Summarize a raw VCSEC acknowledgement; null when the frame isn't one. */
    private fun describeVCSECReply(bytes: ByteArray): String? {
        val msg = try {
            Vcsec.FromVCSECMessage.parseFrom(bytes)
        } catch (e: Exception) {
            return null
        }
        val parts = mutableListOf<String>()
        msg.nominalError?.let { parts.add("车辆报错=${it.genericError.name}") }
        if (msg.hasCommandStatus()) {
            val status = msg.commandStatus
            parts.add("操作状态=${status.operationStatus.name}")
            if (status.hasWhitelistOperationStatus()) {
                val info = status.whitelistOperationStatus.whitelistOperationInformation
                parts.add("钥匙链=${info.name}（${glossWhitelistInfo(info)}）")
            }
            if (status.hasSignedMessageStatus()) {
                parts.add("签名=${status.signedMessageStatus.signedMessageInformation.name}")
            }
        }
        return if (parts.isEmpty()) null else parts.joinToString(" ")
    }

    /**
     * True once the vehicle accepts a handshake with our key. A timeout is deliberately NOT
     * treated as "not paired" — during the NFC wait the car may simply be busy or waking — so the
     * pairing loop keeps waiting instead of reporting a false failure.
     */
    suspend fun isKeyPaired(): Boolean {
        val wasPaired = _keyPaired.value
        return try {
            ensureSession(UniversalMessage.Domain.DOMAIN_VEHICLE_SECURITY)
            _keyPaired.value = true
            if (!wasPaired) onPairingConfirmed()
            true
        } catch (e: TeslaError.KeyNotPaired) {
            _keyPaired.value = false
            false
        } catch (e: Exception) {
            // Polled every 3s during the NFC wait: report the "couldn't tell" case once per link
            // so the log stays readable.
            if (!pairCheckAnomalyReported) {
                pairCheckAnomalyReported = true
                com.teslalink.app.debug.DebugLog.log("vehicle", "配对检测暂无结果（继续等待）：${e.message}")
            }
            false
        }
    }

    /** First successful handshake after pairing: prove enrollment, then knock the head unit. */
    private fun onPairingConfirmed() {
        com.teslalink.app.debug.DebugLog.log("vehicle", "车辆已接受这把公钥")
        scope.launch {
            auditKeychain()
            val t0 = System.currentTimeMillis()
            try {
                ensureSession(UniversalMessage.Domain.DOMAIN_INFOTAINMENT)
                com.teslalink.app.debug.DebugLog.log(
                    "vehicle", "车机域会话已建立（${System.currentTimeMillis() - t0}ms）"
                )
            } catch (e: Exception) {
                com.teslalink.app.debug.DebugLog.error(
                    "vehicle",
                    "车机域会话未建立（${System.currentTimeMillis() - t0}ms）：${e.message}"
                )
            }
        }
    }

    /**
     * One-shot link diagnosis. Runs every stage of the chain and records what the vehicle
     * actually answered, so a single car trip produces a complete picture.
     */
    suspend fun selfTest(): List<String> {
        val out = mutableListOf<String>()
        val d = dispatcher
            ?: run {
                out.add("未连接：请先在「设置」页连接")
                return out
            }

        suspend fun step(name: String, body: suspend () -> String) {
            val started = System.currentTimeMillis()
            val result = try {
                body()
            } catch (e: com.teslalink.app.protocol.TeslaError.VehicleError) {
                "故障码 ${e.fault.name}"
            } catch (e: Exception) {
                "失败：${e.message ?: e.javaClass.simpleName}"
            }
            val line = "$name → $result（${System.currentTimeMillis() - started}ms）"
            com.teslalink.app.debug.DebugLog.log("selftest", line)
            out.add(line)
        }

        suspend fun handshake(domain: UniversalMessage.Domain, timeoutMs: Long): String {
            if (d.isSessionReady(domain)) return "会话已就绪"
            // Same serialization as the normal path, so a step's measured time is its own RTT.
            return sessionMutex.withLock {
                // Invalidate anything cached so a stale session doesn't hide the real fault.
                d.invalidateSession(domain)
                try {
                    if (d.startSession(domain, timeoutMs)) saveSessions()
                } catch (e: Exception) {
                    // Only the vehicle's own "unknown key" answer says anything about pairing.
                    if (domain == UniversalMessage.Domain.DOMAIN_VEHICLE_SECURITY &&
                        e is TeslaError.KeyNotPaired
                    ) {
                        _keyPaired.value = false
                    }
                    syncSessionFlags()
                    throw e
                }
                if (domain == UniversalMessage.Domain.DOMAIN_VEHICLE_SECURITY) _keyPaired.value = true
                syncSessionFlags()
                "握手成功"
            }
        }

        step("1.VCSEC 安全域会话") { handshake(UniversalMessage.Domain.DOMAIN_VEHICLE_SECURITY, 8_000) }

        step("2.钥匙链条目数（免鉴权）") {
            val reply = executeVCSEC(
                Payloads.informationRequest(
                    Vcsec.InformationRequestType.INFORMATION_REQUEST_TYPE_GET_WHITELIST_INFO
                ),
                Dispatcher.Auth.NONE,
                whitelistOperation = false,
                deadlineMs = 8_000,
            )
            if (reply.hasWhitelistInfo()) {
                "在册 ${reply.whitelistInfo.numberOfEntries} 把，slotMask=${reply.whitelistInfo.slotMask}"
            } else {
                "车辆未返回 whitelistInfo"
            }
        }

        step("3.本机公钥是否在钥匙链") {
            val pub = com.google.protobuf.ByteString.copyFrom(keyManager.publicBytes())
            val payload = Vcsec.UnsignedMessage.newBuilder()
                .setInformationRequest(
                    Vcsec.InformationRequest.newBuilder()
                        .setInformationRequestType(
                            Vcsec.InformationRequestType.INFORMATION_REQUEST_TYPE_GET_WHITELIST_ENTRY_INFO
                        )
                        .setPublicKey(pub)
                )
                .build()
            val reply = executeVCSEC(payload, Dispatcher.Auth.NONE, false, deadlineMs = 8_000)
            if (reply.hasWhitelistEntryInfo()) {
                "在册：角色=${reply.whitelistEntryInfo.keyRole.name} 槽位=${reply.whitelistEntryInfo.slot}"
            } else {
                "车辆不认识这把公钥（配对没有真正写入）"
            }
        }

        step("4.车机域会话（不唤醒）") { handshake(UniversalMessage.Domain.DOMAIN_INFOTAINMENT, 6_000) }

        step("5.RKE 唤醒整车") {
            wake()
            "唤醒指令已确认"
        }

        step("6.车机域会话（唤醒后）") { handshake(UniversalMessage.Domain.DOMAIN_INFOTAINMENT, 25_000) }

        step("7.CarServer Ping") {
            executeCarServer(Payloads.ping())
            "车机接受了鉴权指令"
        }

        step("8.遥测 DRIVE 单类") {
            val response = executeCarServer(
                Payloads.getVehicleData(listOf(Payloads.Category.DRIVE))
            )
            "actionStatus=${response.actionStatus.result.name} driveState=${response.vehicleData.hasDriveState()}"
        }

        step("9.遥测 全类别（预期 MTU 超限：验证必须按类别拆分）") {
            val response = executeCarServer(
                Payloads.getVehicleData(
                    listOf(
                        Payloads.Category.DRIVE,
                        Payloads.Category.CHARGE,
                        Payloads.Category.CLIMATE,
                        Payloads.Category.CLOSURES,
                        Payloads.Category.LOCATION,
                    )
                )
            )
            val vd = response.vehicleData
            "drive=${vd.hasDriveState()} charge=${vd.hasChargeState()} climate=${vd.hasClimateState()}"
        }

        return out
    }

    companion object {
        const val DEFAULT_FLAGS = 1 shl 1 // FLAG_ENCRYPT_RESPONSE
        const val RETRY_INTERVAL_MS = 1_000L
    }
}
