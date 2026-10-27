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
import android.bluetooth.le.ScanSettings
import android.content.Context
import com.teslalink.app.protocol.Transport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.resume

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

    private var gatt: BluetoothGatt? = null
    private var txChar: BluetoothGattCharacteristic? = null
    private var blockLength = DEFAULT_MTU - 3
    private var inputBuffer = ByteArray(0)
    private var lastRxAt = 0L
    private var currentWrite: CompletableDeferred<Boolean>? = null
    private val txMutex = Mutex()
    @Volatile private var closed = false
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
        connectFailReason = null
        _state.value = ConnectionState.CONNECTING
        closed = false
        connectGatt(device)
        try {
            withTimeout(gattTimeoutMs) {
                while (_state.value != ConnectionState.CONNECTED) {
                    if (_state.value == ConnectionState.DISCONNECTED) {
                        throw GattFailedException(connectFailReason)
                    }
                    delay(50)
                }
            }
        } catch (e: GattFailedException) {
            releaseGatt()
            throw IllegalStateException(
                "GATT 连接被设备断开${e.reason?.let { "：$it" } ?: "（BLE 连接已满、设备不在范围或蓝牙栈抖动）"}"
            )
        } catch (e: TimeoutCancellationException) {
            releaseGatt()
            throw IllegalStateException(
                "GATT 连接在 ${gattTimeoutMs / 1000} 秒内未完成" +
                    "${connectFailReason?.let { "：$it" } ?: "（连接已满或车机休眠）"}"
            )
        }
    }

    /**
     * 失败路径必须 close。只 disconnect() 的话 BluetoothGatt 的回调还注册着，而
     * 「记住的地址直连失败 → 回退扫描重连」会在同一个 transport 上再开一个 GATT，
     * 前一个再也没人释放：手机侧攒僵尸连接，车那边 3 条上限也被占着，
     * 表现就是越试越连不上（status=133 / 一直超时）。
     */
    private fun releaseGatt() {
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        _state.value = ConnectionState.DISCONNECTED
    }

    private class GattFailedException(val reason: String?) :
        IllegalStateException("GATT 连接被断开: $reason")

    private suspend fun connectGatt(device: BluetoothDevice) = suspendCancellableCoroutine { cont ->
        val log = fun(msg: String) = com.teslalink.app.debug.DebugLog.log("ble-gatt", msg)
        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                log("onConnectionStateChange status=$status newState=$newState")
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> g.requestMtu(MAX_MTU)
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        _state.value = ConnectionState.DISCONNECTED
                        inputBuffer = ByteArray(0)
                        if (!cont.isCompleted) {
                            cont.resume(Unit)
                        }
                        currentWrite?.complete(false)
                        currentWrite = null
                    }
                }
            }

            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                log("onMtuChanged mtu=$mtu status=$status")
                blockLength = (if (status == BluetoothGatt.GATT_SUCCESS) mtu else DEFAULT_MTU)
                    .coerceAtMost(1024) - 3
                g.discoverServices()
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                log("onServicesDiscovered status=$status services=${g.services?.size}")
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    connectFailReason = "服务发现失败 status=$status"
                    g.disconnect()
                    return
                }
                val service = g.getService(VEHICLE_SERVICE_UUID)
                    ?: g.services?.firstOrNull { containsVehicleService(listOf(it.uuid)) }
                if (service == null) {
                    connectFailReason = "该设备没有车辆服务 " +
                        "(实际服务: ${g.services?.map { it.uuid?.toString() } ?: "无"})"
                    log("未发现车辆服务 $VEHICLE_SERVICE_UUID；实际服务: ${g.services?.map { it.uuid }}")
                    g.disconnect()
                    return
                }
                txChar = service.getCharacteristic(TO_VEHICLE_UUID)
                val rxChar = service.getCharacteristic(FROM_VEHICLE_UUID)
                if (txChar == null || rxChar == null) {
                    connectFailReason = "车辆服务存在但读写特征缺失 (tx=${txChar != null} rx=${rxChar != null})"
                    log("服务存在但特征缺失 tx=${txChar != null} rx=${rxChar != null}")
                    g.disconnect()
                    return
                }
                g.setCharacteristicNotification(rxChar, true)
                val cccd = rxChar.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG)
                if (cccd == null) {
                    // 极少数车机不给 CCCD 描述符：只能退化成"信任通知会来"。
                    _state.value = ConnectionState.CONNECTED
                    return
                }
                // 订阅请求本身可能失败（蓝牙栈抖动/缓冲不足）。旧实现无视结果直接置
                // CONNECTED——通知一个都收不到，链路"假在线"，之后所有命令只表现为超时。
                val queued: Boolean = if (android.os.Build.VERSION.SDK_INT >= 33) {
                    // API 33 起这个重载返回 int 状态码（不是 Boolean）。
                    g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                        BluetoothGatt.GATT_SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(cccd)
                }
                if (!queued) {
                    connectFailReason = "订阅车辆通知失败（writeDescriptor 没排上队）"
                    log("CCCD 订阅未入队，连接判定失败")
                    g.disconnect()
                }
            }

            override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                log("onDescriptorWrite desc=${descriptor.uuid} status=$status")
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    connectFailReason = "订阅车辆通知失败 status=$status"
                    // 断开让外层超时/失败路径带着原因走，而不是"假在线"等每个命令超时。
                    g.disconnect()
                    return
                }
                _state.value = ConnectionState.CONNECTED
            }

            override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                com.teslalink.app.debug.DebugLog.log("ble-gatt", "onCharacteristicWrite status=$status")
                currentWrite?.complete(status == BluetoothGatt.GATT_SUCCESS)
                currentWrite = null
            }

            override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
                if (characteristic.uuid == FROM_VEHICLE_UUID) {
                    onRx(value)
                }
            }

            @Deprecated("pre-T callback")
            override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                if (characteristic.uuid == FROM_VEHICLE_UUID) {
                    @Suppress("DEPRECATION")
                    val value = characteristic.value ?: return
                    onRx(value)
                }
            }
        }
        this.gatt = device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
        cont.resume(Unit)
    }

    // ---- Framing -----------------------------------------------------------

    private fun onRx(chunk: ByteArray) {
        val now = SystemClockMono.now()
        if (now - lastRxAt > 1000) {
            inputBuffer = ByteArray(0)
        }
        lastRxAt = now
        inputBuffer += chunk
        while (flush()) { /* drain */ }
    }

    private fun flush(): Boolean {
        if (inputBuffer.size >= 2) {
            val msgLength = (inputBuffer[0].toInt() and 0xFF) * 256 + (inputBuffer[1].toInt() and 0xFF)
            if (msgLength > MAX_MESSAGE_SIZE) {
                com.teslalink.app.debug.DebugLog.error("ble-framing", "非法帧长度 $msgLength，丢弃缓冲区")
                inputBuffer = ByteArray(0)
                return false
            }
            if (inputBuffer.size >= 2 + msgLength) {
                val frame = inputBuffer.copyOfRange(2, 2 + msgLength)
                inputBuffer = inputBuffer.copyOfRange(2 + msgLength, inputBuffer.size)
                com.teslalink.app.debug.DebugLog.v(
                    "ble-rx",
                    "RX ${frame.size}B: ${com.teslalink.app.debug.DebugLog.hex(frame, 200)}"
                )
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

    override suspend fun send(buffer: ByteArray) {
        txMutex.withLock {
            val g = gatt ?: throw IllegalStateException("BLE 未连接")
            val tx = txChar ?: throw IllegalStateException("BLE 特征未就绪")
            com.teslalink.app.debug.DebugLog.v(
                "ble-tx",
                "TX ${buffer.size}B blockLen=$blockLength: ${com.teslalink.app.debug.DebugLog.hex(buffer, 200)}"
            )
            // 长度头只有两字节：超过 65535 会被静默截断成错误的帧长（对端只当坏帧，现场极难查）。
            // 这里按头部真实容量设限，不用 Rx 侧那个 1024 的接收上限——发送体是 protobuf 指令，
            // 个别命令（带签名/带元数据）可以合法地比它大。
            require(buffer.size <= MAX_TX_BODY) { "发送体 ${buffer.size}B 超过 2 字节长度头的上限 $MAX_TX_BODY B" }
            val out = byteArrayOf(
                ((buffer.size shr 8) and 0xFF).toByte(),
                (buffer.size and 0xFF).toByte(),
            ) + buffer
            var block = blockLength
            var offset = 0
            while (offset < out.size) {
                if (block > out.size - offset) block = out.size - offset
                val chunk = out.copyOfRange(offset, offset + block)
                val ok = writeChunk(g, tx, chunk)
                if (!ok) throw IllegalStateException("BLE 写入失败")
                offset += block
            }
        }
    }

    private suspend fun writeChunk(
        g: BluetoothGatt,
        tx: BluetoothGattCharacteristic,
        chunk: ByteArray,
    ): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        currentWrite = deferred
        val accepted: Boolean = if (android.os.Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(tx, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            tx.value = chunk
            @Suppress("DEPRECATION")
            tx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            g.writeCharacteristic(tx)
        }
        if (!accepted) {
            currentWrite = null
            return false
        }
        // Must time out: a dropped onCharacteristicWrite callback (common on OEM stacks) would
        // otherwise leave this deferred pending forever and wedge txMutex for the whole session.
        return try {
            kotlinx.coroutines.withTimeout(WRITE_TIMEOUT_MS) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            com.teslalink.app.debug.DebugLog.error(
                "ble-gatt",
                "写入回执超时（${WRITE_TIMEOUT_MS}ms），放弃本次写"
            )
            false
        } finally {
            if (currentWrite === deferred) currentWrite = null
        }
    }

    fun disconnect() {
        closed = true
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: Exception) { }
        gatt = null
        _state.value = ConnectionState.DISCONNECTED
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
