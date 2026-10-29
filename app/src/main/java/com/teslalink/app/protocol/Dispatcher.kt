package com.teslalink.app.protocol

import com.google.protobuf.ByteString
import com.tesla.generated.signatures.Signatures
import com.tesla.generated.universalmessage.UniversalMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharedFlow
import java.security.PrivateKey
import java.util.concurrent.ConcurrentHashMap

/** Transport-agnostic datagram pipe (BLE, or in-memory for tests). */
interface Transport {
    val incoming: SharedFlow<ByteArray>
    suspend fun send(buffer: ByteArray)
}

class Dispatcher(
    private val vin: String,
    val privateKey: PrivateKey?,
    val localPublicBytes: ByteArray?,
    private val transport: Transport,
) {
    enum class Auth { NONE, GCM }

    class Handler internal constructor(
        val domain: Int,
        internal val address: ByteArray,
        internal val uuid: ByteArray?,
        internal val key: String,
        internal var requestHash: ByteArray?,
        internal val requestSentAtMs: Long,
        val channel: Channel<UniversalMessage.RoutableMessage>,
    ) {
        internal val antireplay = SlidingWindow()
    }

    private class SessionSlot {
        @Volatile var signer: TeslaSession? = null
        @Volatile var ready = false
    }

    private val sessions = ConcurrentHashMap<Int, SessionSlot>()
    private val handlers = ConcurrentHashMap<String, Handler>()
    private val clientAddress = TeslaCrypto.randomBytes(ADDRESS_LENGTH)
    private var listenJob: Job? = null

    /** Vehicle replies we couldn't route: reported for the first few, then silenced. */
    @Volatile private var droppedReplies = 0

    private fun noteDropped(reason: String) {
        if (droppedReplies < DROPPED_LOG_LIMIT) {
            com.teslalink.app.debug.DebugLog.log("dispatcher", "RX 丢弃：$reason")
        }
        droppedReplies++
    }

    /** Max delay between a session-info request and its accepted reply. */
    var maxLatencyMs = 4000L

    fun start(scope: CoroutineScope) {
        listenJob?.cancel()
        listenJob = scope.launch {
            transport.incoming.collect { bytes ->
                val message = try {
                    UniversalMessage.RoutableMessage.parseFrom(bytes)
                } catch (e: Exception) {
                    return@collect
                }
                try {
                    process(message)
                } catch (e: Exception) {
                    // Never let a bad datagram kill the listener.
                }
            }
        }
    }

    fun stop() {
        listenJob?.cancel()
        listenJob = null
        handlers.values.forEach { it.channel.close(TeslaError.NotConnected()) }
        handlers.clear()
    }

    // ---- Sessions ----------------------------------------------------------

    /**
     * Perform the handshake with a vehicle domain. Retries until [deadlineMs]
     * elapses. Returns true if a handshake actually happened (vs. session already ready).
     * Port of dispatcher.StartSession.
     */
    suspend fun startSession(domain: UniversalMessage.Domain, deadlineMs: Long = 15_000): Boolean {
        val privateKey = privateKey ?: throw TeslaError.NoSession()
        val local = localPublicBytes ?: throw TeslaError.NoSession()
        val slot = sessions.computeIfAbsent(domain.number) { SessionSlot() }
        if (slot.ready) return false

        val deadline = System.currentTimeMillis() + deadlineMs
        var lastError: Throwable? = null
        com.teslalink.app.debug.DebugLog.log("dispatcher", "握手开始 domain=${domain.name} deadline=${deadlineMs}ms")
        while (System.currentTimeMillis() < deadline) {
            val handler = send(sessionInfoRequest(domain, local), Auth.NONE)
            try {
                // Wait at least maxLatency: a reply that lands after we give up is discarded by
                // checkForSessionUpdate as "too late" even though it was perfectly good.
                val waitMs = minOf(4_000L, deadline - System.currentTimeMillis())
                if (waitMs <= 0) break
                val reply = kotlinx.coroutines.withTimeoutOrNull(waitMs) { handler.channel.receive() }
                if (reply != null) {
                    // The car signs session info even for a key that isn't whitelisted, so the
                    // tag can verify while the status says "unknown key". Check the error before
                    // accepting readiness, otherwise pairing detection lies to the UI.
                    val err = protocolErrorOf(reply)
                    if (err != null && err !is TeslaError.Busy) {
                        lastError = err
                        com.teslalink.app.debug.DebugLog.error(
                            "dispatcher",
                            "握手响应 domain=${domain.name}: ${err.message}"
                        )
                        if (!err.temporary) throw err
                    }
                }
                if (slot.ready) return true
            } finally {
                closeHandler(handler)
            }
        }
        com.teslalink.app.debug.DebugLog.error("dispatcher", "握手失败 domain=${domain.name}: ${lastError?.message}")
        throw lastError ?: TeslaError.Timeout(false)
    }

    fun isSessionReady(domain: UniversalMessage.Domain): Boolean =
        sessions[domain.number]?.ready == true

    fun invalidateSession(domain: UniversalMessage.Domain) {
        sessions[domain.number]?.let { it.ready = false; it.signer = null }
    }

    /** Session info request builder. */
    private fun sessionInfoRequest(
        domain: UniversalMessage.Domain,
        localPublic: ByteArray,
    ): UniversalMessage.RoutableMessage.Builder =
        UniversalMessage.RoutableMessage.newBuilder()
            .setToDestination(
                UniversalMessage.Destination.newBuilder().setDomain(domain)
            )
            .setSessionInfoRequest(
                UniversalMessage.SessionInfoRequest.newBuilder()
                    .setPublicKey(ByteString.copyFrom(localPublic))
            )

    // ---- Sending -----------------------------------------------------------

    /**
     * Send a message to the vehicle and register a handler for its response.
     * Port of dispatcher.Send. The caller must have set `to_destination`.
     */
    suspend fun send(
        builder: UniversalMessage.RoutableMessage.Builder,
        auth: Auth,
        lifetimeSeconds: Long = 5,
    ): Handler {
        val domain = builder.toDestination?.domain ?: UniversalMessage.Domain.DOMAIN_BROADCAST
        check(domain != UniversalMessage.Domain.DOMAIN_BROADCAST) { "message without destination domain" }
        val isVCSEC = domain == UniversalMessage.Domain.DOMAIN_VEHICLE_SECURITY
        val address = if (isVCSEC) TeslaCrypto.randomBytes(ADDRESS_LENGTH) else clientAddress.copyOf()
        val uuid = TeslaCrypto.randomBytes(UUID_LENGTH)
        builder.uuid = ByteString.copyFrom(uuid)
        builder.fromDestination =
            UniversalMessage.Destination.newBuilder()
                .setRoutingAddress(ByteString.copyFrom(address))
                .build()

        var signer: TeslaSession? = null
        if (auth != Auth.NONE) {
            val slot = sessions[domain.number] ?: throw TeslaError.NoSession()
            signer = slot.signer ?: throw TeslaError.NoSession()
            when (auth) {
                Auth.GCM -> signer.encryptCommand(builder, lifetimeSeconds)
                else -> signer.hmacAuthorize(builder, lifetimeSeconds)
            }
        }

        val key = handlerKey(address, if (isVCSEC) null else uuid)
        val handler = Handler(
            domain = domain.number,
            address = address,
            uuid = uuid,
            key = key,
            requestHash = signer?.requestHashOf(builder.build()),
            requestSentAtMs = System.currentTimeMillis(),
            channel = Channel(16),
        )
        handlers[key] = handler
        com.teslalink.app.debug.DebugLog.v(
            "dispatcher",
            "SEND domain=${domain.name} auth=$auth addr=${com.teslalink.app.debug.DebugLog.hex(address, 16)}" +
                " uuid=${com.teslalink.app.debug.DebugLog.hex(uuid, 16)}"
        )
        try {
            transport.send(builder.build().toByteArray())
        } catch (e: Throwable) {
            closeHandler(handler)
            throw e
        }
        return handler
    }

    // ---- Receiving ---------------------------------------------------------

    private fun process(message: UniversalMessage.RoutableMessage) {
        val from = message.fromDestination ?: return
        val domain = from.domain
        val destination = message.toDestination ?: return
        // Responses are addressed back to our routing address.
        if (!destination.hasRoutingAddress()) {
            noteDropped("响应无路由地址 from=${domain.name} fault=${message.signedMessageStatus?.signedMessageFault}")
            return
        }
        val address = destination.routingAddress.toByteArray()
        if (address.size != ADDRESS_LENGTH) {
            noteDropped("路由地址长度 ${address.size} from=${domain.name}")
            return
        }

        val requestUuid = message.requestUuid
        val key: String = if (domain == UniversalMessage.Domain.DOMAIN_VEHICLE_SECURITY) {
            handlerKey(address, null)
        } else {
            if (requestUuid.isEmpty) {
                noteDropped(
                    "非安全域响应缺 request_uuid from=${domain.name} " +
                        "fault=${message.signedMessageStatus?.signedMessageFault} " +
                        "hasSessionInfo=${message.hasSessionInfo()}"
                )
                return
            }
            handlerKey(address, requestUuid.toByteArray())
        }
        val handler = handlers[key]
        if (handler == null) {
            // Dropped replies are otherwise invisible: the caller only sees a timeout, which hides
            // what the vehicle actually answered. Loud for the first few, then quiet.
            noteDropped(
                "无匹配处理器 from=${domain.name} addr=${com.teslalink.app.debug.DebugLog.hex(address, 8)} " +
                    "uuid=${if (requestUuid.isEmpty) "-" else com.teslalink.app.debug.DebugLog.hex(requestUuid.toByteArray(), 8)} " +
                    "fault=${message.signedMessageStatus?.signedMessageFault} " +
                    "hasSessionInfo=${message.hasSessionInfo()}"
            )
            return
        }
        com.teslalink.app.debug.DebugLog.v(
            "dispatcher",
            "RX domain=${domain.name} fault=${message.signedMessageStatus?.signedMessageFault}" +
                " hasSessionInfo=${message.hasSessionInfo()} encResponse=${message.hasSignatureData() && message.signatureData.hasAESGCMResponseData()}"
        )

        if (message.hasSessionInfo()) {
            checkForSessionUpdate(message, handler)
        }

        var current = message
        // Fault replies (e.g. RESPONSE_MTU_EXCEEDED) carry only a MessageStatus — no payload to
        // decrypt. Deliver them so the caller sees the actual fault instead of a decrypt failure.
        if (message.hasSignatureData() && message.signatureData.hasAESGCMResponseData() &&
            message.protobufMessageAsBytes.size() > 0
        ) {
            val signer = sessions[handler.domain]?.signer
            val requestHash = handler.requestHash
            if (signer == null || requestHash == null) {
                noteDropped(
                    "响应已加密但本机无解密上下文 domain=${handler.domain} " +
                        "signer=${signer != null} hash=${requestHash != null}"
                )
                return
            }
            val counter: Long
            val plaintext: ByteArray
            try {
                val opened = signer.decryptResponse(current, requestHash)
                counter = opened.first
                plaintext = opened.second
            } catch (e: Exception) {
                // Go logs this too: an unreadable reply must not look like "the car never answered".
                noteDropped("响应解密失败 domain=${handler.domain}: ${e.message}")
                return
            }
            if (!handler.antireplay.update(counter)) {
                com.teslalink.app.debug.DebugLog.v("dispatcher", "RX 重复响应（counter=$counter）已丢弃")
                return
            }
            current = current.toBuilder()
                .setProtobufMessageAsBytes(ByteString.copyFrom(plaintext))
                .setSignatureData(Signatures.SignatureData.getDefaultInstance())
                .build()
        }

        handler.channel.trySend(current)
    }

    /**
     * Vehicles may attach fresh session info to any reply (especially errors).
     * Verify its HMAC tag and resync. Port of dispatcher.checkForSessionUpdate.
     */
    private fun checkForSessionUpdate(message: UniversalMessage.RoutableMessage, handler: Handler) {
        val privateKey = privateKey ?: return
        val local = localPublicBytes ?: return
        if (System.currentTimeMillis() - handler.requestSentAtMs > maxLatencyMs) return
        if (!message.hasSignatureData() || !message.signatureData.hasSessionInfoTag()) return
        val tag = message.signatureData.sessionInfoTag.tag
        if (tag.isEmpty) return

        val slot = sessions[handler.domain] ?: return
        val encodedInfo = message.sessionInfo.toByteArray()
        val challenge = message.requestUuid.toByteArray()

        val verified = try {
            val info = Signatures.SessionInfo.parseFrom(encodedInfo)
            if (slot.ready && slot.signer != null) {
                val ok = slot.signer!!.verifySessionInfoTag(challenge, encodedInfo, tag.toByteArray())
                if (ok) slot.signer!!.updateFromSessionInfo(info)
                ok
            } else {
                val candidate = TeslaSession.create(privateKey, local, vin, info)
                val ok = candidate.verifySessionInfoTag(challenge, encodedInfo, tag.toByteArray())
                // Only a status=OK session info means the vehicle accepted our key.
                if (ok && info.status == Signatures.Session_Info_Status.SESSION_INFO_STATUS_OK) {
                    slot.signer = candidate
                    slot.ready = true
                }
                ok
            }
        } catch (e: Exception) {
            false
        }
        if (!verified) {
            // Discard unauthenticated session info.
        }
    }

    fun closeHandler(handler: Handler) {
        handlers.remove(handler.key)
        handler.channel.close()
    }

    // ---- Session cache -----------------------------------------------------

    data class CachedSession(val domain: Int, val encodedInfo: ByteArray, val createdAtWallMs: Long)

    fun exportCache(): List<CachedSession> =
        sessions.entries.mapNotNull { (domain, slot) ->
            val signer = slot.signer ?: return@mapNotNull null
            // Never persist a half-negotiated session: loadCache() would treat it as paired.
            if (!slot.ready) return@mapNotNull null
            CachedSession(domain, signer.export(), System.currentTimeMillis())
        }

    fun loadCache(entries: List<CachedSession>) {
        val privateKey = privateKey ?: throw TeslaError.NoSession()
        val local = localPublicBytes ?: throw TeslaError.NoSession()
        sessions.clear()
        entries.forEach { entry ->
            val signer = TeslaSession.import(privateKey, local, vin, entry.encodedInfo, entry.createdAtWallMs)
            val slot = SessionSlot()
            slot.signer = signer
            slot.ready = true
            sessions[entry.domain] = slot
        }
    }

    companion object {
        const val ADDRESS_LENGTH = 16
        const val UUID_LENGTH = 16

        /** How many unroutable vehicle replies get logged before we stop repeating ourselves. */
        const val DROPPED_LOG_LIMIT = 8

        private fun hex(bytes: ByteArray): String =
            bytes.joinToString("") { "%02x".format(it) }

        internal fun handlerKey(address: ByteArray, uuid: ByteArray?): String =
            hex(address) + "|" + (uuid?.let { hex(it) } ?: "")
    }
}
