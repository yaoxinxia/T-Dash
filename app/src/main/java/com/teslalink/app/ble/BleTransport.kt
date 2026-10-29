package com.teslalink.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.teslalink.app.protocol.Transport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest
import java.util.UUID

/**
 * BLE transport implementing the vehicle-command GATT interface.
 *
 * Service:  00000211-b2d1-43f0-9b88-960cebf8b91e
 * To vehicle (write with response): 00000212-...
 * From vehicle (notify):            00000213-...
 * Framing: 2-byte big-endian length prefix; chunks of MTU-3.
 */
@SuppressLint("MissingPermission")
class BleTransport(
    context: Context,
    private val scope: CoroutineScope,
) : Transport {
    private val appContext = context.applicationContext
    private val bluetoothManager: BluetoothManager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter get() = bluetoothManager.adapter

    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    override val incoming: SharedFlow<ByteArray> get() = _incoming

    /**
     * Synchronous frame listeners, used where missing a single frame matters (the add-key
     * acknowledgement). SharedFlow has no replay, so a coroutine collector can subscribe too late.
     */
    private val frameListeners = java.util.concurrent.CopyOnWriteArrayList<(ByteArray) -> Unit>()

    fun addFrameListener(listener: (ByteArray) -> Unit): () -> Unit {
        frameListeners.add(listener)
        return { frameListeners.remove(listener) }
    }

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<ConnectionState> get() = _state

    // disconnect() can be called from any thread. Keep ownership, callbacks and GATT
    // enqueue calls under the same lock; never hold it across a suspension.
    private val gattLock = Any()
    private val callbackHandler = Handler(Looper.getMainLooper())
    private var connection: Connection? = null
    private val txMutex = Mutex()

    private class Connection {
        val session = GattSession<BluetoothGatt>()
        var tx: BluetoothGattCharacteristic? = null
        var rx: BluetoothGattCharacteristic? = null
        var cccd: BluetoothGattDescriptor? = null
        var cccdQueued = false
        var blockLength = DEFAULT_MTU - 3
        var inputBuffer = ByteArray(0)
        var lastRxAt = 0L
    }

    /** Why the last GATT setup failed, e.g. "车辆服务未找到" — surfaced in errors. */
    @Volatile var connectFailReason: String? = null
        private set

    enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }

    // ---- Scanning ----------------------------------------------------------

    /** Advertised local name for a VIN: "S" + SHA1(VIN)[:8] hex + "C". */
    fun vehicleLocalName(vin: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(vin.encodeToByteArray())
        return "S" + digest.take(8).joinToString("") { "%02x".format(it) } + "C"
    }

    /** Resolve a device by MAC (used when the operator picks a candidate manually). */
    fun deviceFromAddress(address: String): BluetoothDevice {
        ensurePermissions()
        return try {
            adapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException("MAC 地址格式错误: $address", e)
        }
    }

    /** Verify runtime permissions that BLE scanning/connecting depends on. */
    private fun ensurePermissions() {
        // Matches nRF Connect's posture: without location permission, some OEM stacks
        // (OriginOS etc.) run the scan in a restricted mode that omits devices.
        val needed = mutableListOf(
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
        )
        if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.R) {
            needed.add(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        val missing = needed.filter {
            androidx.core.content.ContextCompat.checkSelfPermission(appContext, it) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            throw IllegalStateException(
                "缺少蓝牙/定位权限（$missing）。请允许「附近的设备」和「位置信息」，" +
                    "并确认系统定位服务（下拉快捷开关）已开启，然后重试。"
            )
        }
    }

    suspend fun scanForVehicle(
        vin: String,
        timeoutMs: Long = 12_000,
        customName: String? = null,
    ): android.bluetooth.le.ScanResult {
        ensurePermissions()
        val scanner = adapter.bluetoothLeScanner
            ?: throw IllegalStateException("手机蓝牙未开启")
        val name = vehicleLocalName(vin)
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            // Some stacks default to FIRST_MATCH, which skips devices already present in the
            // controller cache; ALL reports every advertisement.
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        val found = callbackFlow {
            var seen = 0
            // The name may arrive in the scan response while the service UUID arrives in the
            // ADV packet (or the other way round), so accumulate per address and match on the
            // merged view instead of on a single packet.
            val names = HashMap<String, String>()
            val uuids = HashMap<String, MutableSet<java.util.UUID>>()
            val logged = HashSet<String>()
            val loggedSignatures = HashSet<String>()
            val hitLogged = HashSet<String>()
            val callback = object : android.bluetooth.le.ScanCallback() {
                override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
                    seen++
                    val addr = result.device.address
                    result.scanRecord?.deviceName?.let { names[addr] = it }
                    val set = uuids.getOrPut(addr) { mutableSetOf() }
                    result.scanRecord?.serviceUuids?.forEach { set.add(it.uuid) }
                    val mergedName = names[addr]
                    val mergedUuids = set.toList()
                    val signature = "$mergedName|$mergedUuids"
                    if (logged.add(addr)) {
                        // 每台设备只在首次出现时记一条。
                        com.teslalink.app.debug.DebugLog.log(
                            "ble-scan",
                            "设备: name=${mergedName ?: "??"} addr=$addr rssi=${result.rssi} " +
                                "可连=${result.isConnectable} uuids=$mergedUuids"
                        )
                    } else if (loggedSignatures.add("$addr|$signature")) {
                        // Name/UUID merged later (scan response): one extra line, then quiet.
                        com.teslalink.app.debug.DebugLog.log(
                            "ble-scan",
                            "信息补全: name=${mergedName ?: "??"} addr=$addr uuids=$mergedUuids"
                        )
                    }
                    val reason = when {
                        mergedName?.equals(name, ignoreCase = true) == true -> "名称匹配"
                        customName != null && mergedName?.equals(customName, ignoreCase = true) == true ->
                            "自定义名称匹配"
                        containsVehicleService(mergedUuids) -> "服务UUID匹配"
                        mergedName?.matches(Regex("^S[0-9a-fA-F]{16}C$")) == true -> "车辆命名格式但不是本车"
                        else -> null
                    }
                    if (reason != null && hitLogged.add(addr)) {
                        com.teslalink.app.debug.DebugLog.log(
                            "ble-scan",
                            "命中($reason) name=${mergedName ?: "??"} addr=$addr rssi=${result.rssi}"
                        )
                        // Only dial real candidates; log other Tesla-format devices.
                        if (reason != "车辆命名格式但不是本车") trySend(result)
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    com.teslalink.app.debug.DebugLog.error("ble-scan", "扫描失败 errorCode=$errorCode")
                    close(
                        IllegalStateException(
                            "BLE 扫描启动失败（错误码 $errorCode）：请检查蓝牙开关与「位置信息/附近的设备」权限"
                        )
                    )
                }
            }
            com.teslalink.app.debug.DebugLog.log(
                "ble-scan",
                "开始扫描 期望广播名=$name${customName?.let { " 自定义=$it" } ?: ""}（含服务UUID判据）"
            )
            try {
                scanner.startScan(null, settings, callback)
            } catch (e: SecurityException) {
                com.teslalink.app.debug.DebugLog.error("ble-scan", "startScan 权限被拒", e)
                close(IllegalStateException("BLE 扫描被拒绝：缺少蓝牙/位置权限", e))
                return@callbackFlow
            }
            awaitClose {
                com.teslalink.app.debug.DebugLog.log("ble-scan", "停止扫描（共收到 $seen 条广播）")
                try { scanner.stopScan(callback) } catch (_: Exception) {}
            }
        }
        try {
            return withTimeout(timeoutMs) { found.first() }
        } catch (e: TimeoutCancellationException) {
            throw IllegalStateException(
                "${timeoutMs / 1000} 秒内未发现车辆广播（期望名称 $name，也检查了车辆服务 UUID）。" +
                    "注意：车辆处于唤醒/行驶状态、或官方 App 已占用 BLE 连接时，VCSEC 可能不再广播。" +
                    "可尝试：① 锁车等待 2-3 分钟让车辆休眠后重试；② 完全退出官方 App；" +
                    "③ 用 nRF Connect 在车旁看到车辆后，把它的 MAC 填到「设置」页保存以跳过扫描。"
            )
        }
    }

    // ---- Connection --------------------------------------------------------

    suspend fun connect(vin: String, timeoutMs: Long = 20_000, customName: String? = null): BluetoothDevice {
        val scanResult = scanForVehicle(vin, timeoutMs = timeoutMs * 3 / 5, customName = customName)
        if (!scanResult.isConnectable) {
            throw IllegalStateException(
                "车辆广播为不可连接状态：BLE 连接可能已满（钥匙+官方 App 共 3 个上限），" +
                    "请关闭官方 App 的蓝牙连接后重试"
            )
        }
        connectDevice(scanResult.device, timeoutMs * 2 / 5)
        return scanResult.device
    }

    /** Connect to an explicit device (used by the debug-page probe). */
    suspend fun connectDevice(device: BluetoothDevice, gattTimeoutMs: Long = 12_000) {
        val attempt = synchronized(gattLock) {
            val previous = connection
            Connection().also {
                // Install the new owner before waking any of the old owner's waiters.
                connection = it
                connectFailReason = null
                previous?.let { old -> releaseGatt(old, IllegalStateException("GATT 被新连接替换")) }
                _state.value = ConnectionState.CONNECTING
            }
        }
        withGattCleanup(release = { failure ->
            synchronized(gattLock) {
                // A cancelled/late attempt must never tear down a newer connection.
                if (connection === attempt && failure !is CancellationException) {
                    connectFailReason = failure.message
                }
                releaseGatt(attempt, failure)
            }
        }) {
            withTimeoutOrNull(gattTimeoutMs) {
                // Non-immediate dispatcher: completing a callback posts the next setup step,
                // rather than making another Android request inside the current callback.
                withContext(Dispatchers.Main) { connectGatt(device, attempt) }
                true
            } ?: throw IllegalStateException("GATT 连接在 ${gattTimeoutMs / 1000} 秒内未完成")
            withConnection(attempt) {
                check(attempt.session.ready && _state.value == ConnectionState.CONNECTED) { "GATT 连接已断开" }
            }
        }
    }

    /** Detach before any stack calls or waiter resumption; close even if disconnect throws. */
    private fun releaseGatt(attempt: Connection, cause: Throwable): Unit = synchronized(gattLock) {
        val wasCurrent = connection === attempt
        if (wasCurrent) connection = null
        val detached = attempt.session.close(cause)
        attempt.tx = null
        attempt.rx = null
        attempt.cccd = null
        attempt.cccdQueued = false
        attempt.blockLength = DEFAULT_MTU - 3
        attempt.inputBuffer = ByteArray(0)
        attempt.lastRxAt = 0L
        if (detached != null) {
            runCatching { detached.disconnect() }
            runCatching { detached.close() }
        }
        if (wasCurrent && connection == null) _state.value = ConnectionState.DISCONNECTED
    }

    private fun <T> withConnection(attempt: Connection, action: () -> T): T = synchronized(gattLock) {
        attempt.session.checkOpen()
        check(connection === attempt) { "GATT 连接已被替换" }
        action()
    }

    private suspend fun setupStep(
        attempt: Connection,
        step: GattSession.Step,
        start: suspend () -> Unit,
    ): GattSession.Reply {
        val waiter = withConnection(attempt) { attempt.session.begin(step) }
        start()
        val reply = waiter.await()
        withConnection(attempt) { attempt.session.finish(step) }
        return reply
    }

    private suspend fun connectGatt(device: BluetoothDevice, attempt: Connection) {
        setupStep(attempt, GattSession.Step.CONNECT) {
            withConnection(attempt) {
                val g = device.connectGatt(
                    appContext, false, callbackFor(attempt), BluetoothDevice.TRANSPORT_LE,
                    BluetoothDevice.PHY_LE_1M_MASK, callbackHandler,
                ) ?: throw IllegalStateException("connectGatt 未返回句柄")
                attempt.session.attach(g)
            }
        }
        val mtu = setupStep(attempt, GattSession.Step.MTU) {
            withConnection(attempt) {
                if (!checkNotNull(attempt.session.handle).requestMtu(MAX_MTU)) {
                    logGatt("requestMtu 未入队，使用默认 MTU=$DEFAULT_MTU")
                    attempt.session.report(GattSession.Step.MTU, GattSession.Reply(0, DEFAULT_MTU))
                }
            }
        }
        withConnection(attempt) {
            attempt.blockLength = (if (mtu.status == BluetoothGatt.GATT_SUCCESS) mtu.mtu else DEFAULT_MTU)
                .coerceIn(DEFAULT_MTU, MAX_MTU) - 3
        }
        setupStep(attempt, GattSession.Step.SERVICES) {
            withConnection(attempt) {
                check(checkNotNull(attempt.session.handle).discoverServices()) { "服务发现失败（discoverServices 未入队）" }
            }
        }
        withConnection(attempt) {
            val g = checkNotNull(attempt.session.handle)
            val service = g.getService(VEHICLE_SERVICE_UUID)
                ?: g.services?.firstOrNull { isVehicleServiceUuid(it.uuid) }
                ?: throw IllegalStateException("该设备没有车辆服务 (实际服务: ${g.services?.map { it.uuid }})")
            attempt.tx = service.getCharacteristic(TO_VEHICLE_UUID)
            attempt.rx = service.getCharacteristic(FROM_VEHICLE_UUID)
            val rx = attempt.rx
            check(attempt.tx != null && rx != null) {
                "车辆服务存在但读写特征缺失 (tx=${attempt.tx != null} rx=${rx != null})"
            }
            attempt.cccd = rx.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG)
                ?: throw IllegalStateException("订阅车辆通知失败（缺少 CCCD 描述符）")
            check(g.setCharacteristicNotification(rx, true)) {
                "订阅车辆通知失败（setCharacteristicNotification 返回 false）"
            }
        }
        setupStep(attempt, GattSession.Step.CCCD) {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                val status = retryGattBusy(BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY) {
                    withConnection(attempt) {
                        val status = checkNotNull(attempt.session.handle).writeDescriptor(
                            checkNotNull(attempt.cccd), BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE,
                        )
                        attempt.cccdQueued = status == BluetoothStatusCodes.SUCCESS
                        logGatt("writeDescriptor statusCode=$status queued=${attempt.cccdQueued}")
                        status
                    }
                }
                check(status == BluetoothStatusCodes.SUCCESS) {
                    "订阅车辆通知失败（writeDescriptor 未入队 statusCode=$status）"
                }
            } else {
                withConnection(attempt) {
                    val cccd = checkNotNull(attempt.cccd)
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    val queued = checkNotNull(attempt.session.handle).writeDescriptor(cccd)
                    attempt.cccdQueued = queued
                    check(queued) { "订阅车辆通知失败（writeDescriptor 返回 false，未入队）" }
                }
            }
        }
        withConnection(attempt) {
            check(attempt.session.ready)
            _state.value = ConnectionState.CONNECTED
        }
    }

    private fun logGatt(message: String) = com.teslalink.app.debug.DebugLog.log("ble-gatt", message)

    private fun callbackFor(attempt: Connection) = object : BluetoothGattCallback() {
        private fun current(g: BluetoothGatt, report: () -> Unit): Unit = synchronized(gattLock) {
            if (connection === attempt && attempt.session.owns(g)) report()
        }

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) = current(g) {
            logGatt("onConnectionStateChange status=$status newState=$newState")
            if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                val cause = IllegalStateException("GATT 连接断开 status=$status newState=$newState")
                if (_state.value == ConnectionState.CONNECTING) connectFailReason = cause.message
                releaseGatt(attempt, cause)
            } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                attempt.session.report(GattSession.Step.CONNECT, GattSession.Reply(status))
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) = current(g) {
            logGatt("onMtuChanged mtu=$mtu status=$status")
            attempt.session.report(GattSession.Step.MTU, GattSession.Reply(status, mtu))
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = current(g) {
            logGatt("onServicesDiscovered status=$status")
            attempt.session.report(GattSession.Step.SERVICES, GattSession.Reply(status))
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) = current(g) {
            if (descriptor === attempt.cccd && attempt.cccdQueued) {
                logGatt("onDescriptorWrite desc=${descriptor.uuid} status=$status")
                attempt.session.report(GattSession.Step.CCCD, GattSession.Reply(status))
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) = current(g) {
            if (characteristic === attempt.tx) {
                // 每个分块一次：必须是惰性日志，否则 250ms 节奏下每秒都在主线程拼字符串+拷日志环。
                com.teslalink.app.debug.DebugLog.v("ble-gatt") { "onCharacteristicWrite status=$status" }
                attempt.session.reportWrite(status)
            }
        }

        // A notification can arrive before the setup coroutine resumes after the CCCD
        // receipt. Preserve it without publishing CONNECTED or allowing sends early.
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) = current(g) {
            if (characteristic === attempt.rx) onRx(attempt, value)
        }

        @Deprecated("pre-T callback")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) = current(g) {
            if (characteristic === attempt.rx) {
                @Suppress("DEPRECATION")
                val value = characteristic.value
                if (value != null) onRx(attempt, value)
            }
        }
    }

    // ---- Framing -----------------------------------------------------------

    private fun onRx(attempt: Connection, chunk: ByteArray) {
        val now = SystemClockMono.now()
        if (now - attempt.lastRxAt > 1000) {
            attempt.inputBuffer = ByteArray(0)
        }
        attempt.lastRxAt = now
        attempt.inputBuffer += chunk
        while (flush(attempt)) { /* drain */ }
    }

    private fun flush(attempt: Connection): Boolean {
        val inputBuffer = attempt.inputBuffer
        if (inputBuffer.size >= 2) {
            val msgLength = (inputBuffer[0].toInt() and 0xFF) * 256 + (inputBuffer[1].toInt() and 0xFF)
            if (msgLength > MAX_MESSAGE_SIZE) {
                com.teslalink.app.debug.DebugLog.error("ble-framing", "非法帧长度 $msgLength，丢弃缓冲区")
                attempt.inputBuffer = ByteArray(0)
                return false
            }
            if (inputBuffer.size >= 2 + msgLength) {
                val frame = inputBuffer.copyOfRange(2, 2 + msgLength)
                attempt.inputBuffer = inputBuffer.copyOfRange(2 + msgLength, inputBuffer.size)
                com.teslalink.app.debug.DebugLog.v("ble-rx") {
                    "RX ${frame.size}B: ${com.teslalink.app.debug.DebugLog.hex(frame, 200)}"
                }
                _incoming.tryEmit(frame)
                // Synchronous listeners: unlike a SharedFlow they cannot miss a frame that
                // arrives before a coroutine gets scheduled (used for the add-key ack).
                frameListeners.forEach { listener -> runCatching { listener(frame) } }
                return true
            }
        }
        return false
    }

    // ---- Sending -----------------------------------------------------------

    override suspend fun send(buffer: ByteArray) = withContext(Dispatchers.Main) {
        txMutex.withLock {
            val attempt = synchronized(gattLock) {
                check(_state.value == ConnectionState.CONNECTED) { "BLE 未连接或通知订阅未完成" }
                checkNotNull(connection).also { check(it.session.ready) }
            }
            // Two-byte length prefix; reject before any part of the frame is sent.
            require(buffer.size <= MAX_TX_BODY) { "发送体 ${buffer.size}B 超过 2 字节长度头的上限 $MAX_TX_BODY B" }
            val blockLength = withConnection(attempt) { attempt.blockLength }
            com.teslalink.app.debug.DebugLog.v("ble-tx") {
                "TX ${buffer.size}B blockLen=$blockLength: ${com.teslalink.app.debug.DebugLog.hex(buffer, 200)}"
            }
            val out = byteArrayOf(
                ((buffer.size shr 8) and 0xFF).toByte(),
                (buffer.size and 0xFF).toByte(),
            ) + buffer
            // Android write callbacks have no request ID. A cancelled/timed-out write
            // cannot share this GATT with another frame, even if earlier chunks succeeded.
            withGattCleanup(release = { releaseGatt(attempt, it) }) {
                var offset = 0
                while (offset < out.size) {
                    val size = minOf(blockLength, out.size - offset)
                    writeChunk(attempt, out.copyOfRange(offset, offset + size))
                    offset += size
                }
            }
        }
    }

    private suspend fun writeChunk(attempt: Connection, chunk: ByteArray) {
        val waiter = withConnection(attempt) {
            check(_state.value == ConnectionState.CONNECTED) { "BLE 未连接" }
            val g = checkNotNull(attempt.session.handle)
            val tx = checkNotNull(attempt.tx)
            val deferred = attempt.session.beginWrite()
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                val status = g.writeCharacteristic(tx, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                check(status == BluetoothStatusCodes.SUCCESS) { "BLE 写入未入队 statusCode=$status" }
            } else {
                @Suppress("DEPRECATION")
                tx.value = chunk
                tx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                val queued = g.writeCharacteristic(tx)
                check(queued) { "BLE 写入未入队（writeCharacteristic 返回 false）" }
            }
            deferred
        }
        withTimeoutOrNull(WRITE_TIMEOUT_MS) { waiter.await() }
            ?: throw IllegalStateException("写入回执超时（${WRITE_TIMEOUT_MS}ms），放弃该 GATT")
        withConnection(attempt) { attempt.session.finishWrite(waiter) }
    }

    fun disconnect() = synchronized(gattLock) {
        connection?.let { releaseGatt(it, IllegalStateException("BLE 主动断开")) }
        Unit
    }

    companion object {
        val VEHICLE_SERVICE_UUID: UUID = UUID.fromString("00000211-b2d1-43f0-9b88-960cebf8b91e")
        val TO_VEHICLE_UUID: UUID = UUID.fromString("00000212-b2d1-43f0-9b88-960cebf8b91e")
        val FROM_VEHICLE_UUID: UUID = UUID.fromString("00000213-b2d1-43f0-9b88-960cebf8b91e")
        val CLIENT_CHARACTERISTIC_CONFIG: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        const val MAX_MTU = 517
        const val DEFAULT_MTU = 23
        const val MAX_MESSAGE_SIZE = 1024

        /** 帧长字段是 2 字节大端：发送体的硬上限。 */
        const val MAX_TX_BODY = 0xFFFF

        /** How long we wait for the stack to confirm a write-with-response chunk. */
        const val WRITE_TIMEOUT_MS = 5_000L

        /**
         * Tesla advertises service 0x0211. Depending on how the vehicle encodes it
         * (full 128-bit vs 16-bit short UUID), Android surfaces either
         * 00000211-b2d1-43f0-9b88-960cebf8b91e or 00000211-0000-1000-8000-00805f9b34fb,
         * so compare the significant 16 bits as well as the full value.
         */
        fun isVehicleServiceUuid(u: UUID): Boolean {
            if (u == VEHICLE_SERVICE_UUID) return true
            // 16-bit short form: 00000211-0000-1000-8000-00805f9b34fb
            val short = (u.mostSignificantBits shr 32) and 0xFFFFL
            return u.mostSignificantBits and 0xFFFF0000L == 0L &&
                u.leastSignificantBits.toULong() == 0x800000805F9B34FBuL && short == 0x0211L
        }

        fun containsVehicleService(uuids: Collection<UUID>): Boolean = uuids.any { isVehicleServiceUuid(it) }
    }
}

private object SystemClockMono {
    fun now(): Long = android.os.SystemClock.elapsedRealtime()
}
