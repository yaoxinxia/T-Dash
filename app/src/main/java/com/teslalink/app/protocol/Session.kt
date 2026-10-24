package com.teslalink.app.protocol

import com.google.protobuf.ByteString
import com.tesla.generated.signatures.Signatures
import com.tesla.generated.universalmessage.UniversalMessage
import java.security.PrivateKey

/**
 * Port of vehicle-command internal/authentication/signer.go (BLE path, AES-GCM).
 *
 * A session is bound to one vehicle domain: it derives the 128-bit AES key K
 * from ECDH(clientKey, domainPublicKey) and tracks the domain's epoch/counter/clock.
 */
class TeslaSession private constructor(
    private val privateKey: PrivateKey,
    val localPublicBytes: ByteArray,
    private val vin: String,
    initialCounter: Long,
    val verifierPublicBytes: ByteArray,
    initialEpoch: ByteArray,
    initialClockTimeSeconds: Long,
    private val importedAtWallMs: Long,
) {
    private val lock = Any()
    private var counter = initialCounter
    private var epoch: ByteArray = initialEpoch.copyOf()
    private var setTimeSeconds = initialClockTimeSeconds

    private val keyK: ByteArray =
        TeslaCrypto.sharedKey(TeslaCrypto.ecdhSharedX(privateKey, verifierPublicBytes))
    private val sessionInfoKey = TeslaCrypto.subkey(keyK, LABEL_SESSION_INFO)
    private val messageAuthKey = TeslaCrypto.subkey(keyK, LABEL_MESSAGE_AUTH)

    /** Vehicle-domain clock expressed in seconds since the current epoch started. */
    val vehicleTimestampSeconds: Long
        get() = (System.currentTimeMillis() - vehicleTimeZeroWallMs) / 1000

    /** Wall-clock time corresponding to vehicle clock 0 for the current epoch. Rebased on resync. */
    private var vehicleTimeZeroWallMs = importedAtWallMs - initialClockTimeSeconds * 1000

    companion object {
        private const val LABEL_SESSION_INFO = "session info"
        private const val LABEL_MESSAGE_AUTH = "authenticated command"

        fun create(
            privateKey: PrivateKey,
            localPublicBytes: ByteArray,
            vin: String,
            info: Signatures.SessionInfo,
            importedAtWallMs: Long = System.currentTimeMillis(),
        ): TeslaSession = TeslaSession(
            privateKey, localPublicBytes, vin,
            info.counter.toLong() and 0xFFFFFFFFL,
            info.publicKey.toByteArray(),
            info.epoch.toByteArray(),
            info.clockTime.toLong() and 0xFFFFFFFFL,
            importedAtWallMs,
        )

        /** Restore a session from a cached SessionInfo blob. [cachedAtWallMs] is when it was exported. */
        fun import(
            privateKey: PrivateKey,
            localPublicBytes: ByteArray,
            vin: String,
            encodedInfo: ByteArray,
            cachedAtWallMs: Long,
        ): TeslaSession {
            val info = Signatures.SessionInfo.parseFrom(encodedInfo)
            return create(privateKey, localPublicBytes, vin, info, cachedAtWallMs)
        }
    }

    /** Verify the session_info HMAC tag returned by the vehicle. */
    fun verifySessionInfoTag(challenge: ByteArray, encodedInfo: ByteArray, tag: ByteArray): Boolean {
        val expected = hmacMetadata(sessionInfoKey, encodedInfo) {
            add(TagDefs.SIGNATURE_TYPE, byteArrayOf(TagDefs.SIG_TYPE_HMAC.toByte()))
            add(TagDefs.PERSONALIZATION, vin.encodeToByteArray())
            add(TagDefs.CHALLENGE, challenge)
        }
        return TeslaCrypto.constantTimeEquals(expected, tag)
    }

    /**
     * Resync with fresh session info carried in an error response.
     * Port of Signer.UpdateSessionInfo.
     */
    fun updateFromSessionInfo(info: Signatures.SessionInfo): Boolean {
        synchronized(lock) {
            val newEpoch = info.epoch.toByteArray()
            if (!newEpoch.contentEquals(epoch) || setTimeSeconds <= info.clockTime) {
                if (counter < (info.counter.toLong() and 0xFFFFFFFFL)) {
                    counter = info.counter.toLong() and 0xFFFFFFFFL
                }
                epoch = newEpoch.copyOf()
                setTimeSeconds = info.clockTime.toLong() and 0xFFFFFFFFL
                // Rebase the vehicle clock, exactly like Go's signer: without this, every later
                // expires_at is computed against the old zero point and the car answers TIME_EXPIRED
                // forever after an epoch change (head unit reboot, vehicle sleep/wake).
                vehicleTimeZeroWallMs = System.currentTimeMillis() - setTimeSeconds * 1000
            }
        }
        return true
    }

    /**
     * Encrypt the message payload in place (AES-GCM, personalized metadata).
     * Port of Signer.Encrypt / encryptWithCounter.
     */
    fun encryptCommand(builder: UniversalMessage.RoutableMessage.Builder, lifetimeSeconds: Long) {
        val plaintext = builder.protobufMessageAsBytes.toByteArray()
        // Take counter, epoch and clock under one lock (Go holds the session lock across the whole
        // Encrypt): reading them separately can pair a new counter with an old epoch during a
        // concurrent resync, which the vehicle rejects with INCORRECT_EPOCH.
        val counterValue: Long
        val epochCopy: ByteArray
        val expiresAt: Long
        synchronized(lock) {
            check(counter != 0xFFFFFFFFL) { "counter rollover" }
            counter += 1
            counterValue = counter
            epochCopy = epoch.copyOf()
            expiresAt = (System.currentTimeMillis() - vehicleTimeZeroWallMs) / 1000 + lifetimeSeconds
        }

        val aad = sha256Metadata {
            add(TagDefs.SIGNATURE_TYPE, byteArrayOf(TagDefs.SIG_TYPE_AES_GCM_PERSONALIZED.toByte()))
            add(TagDefs.DOMAIN, byteArrayOf(builder.toDestination.domain.number.toByte()))
            add(TagDefs.PERSONALIZATION, vin.encodeToByteArray())
            add(TagDefs.EPOCH, epochCopy)
            addUint32(TagDefs.EXPIRES_AT, expiresAt)
            addUint32(TagDefs.COUNTER, counterValue)
            if (builder.flags != 0) addUint32(TagDefs.FLAGS, builder.flags.toLong())
        }
        val (nonce, ciphertext, tag) = TeslaCrypto.gcmSeal(keyK, plaintext, aad)

        builder.setProtobufMessageAsBytes(ByteString.copyFrom(ciphertext))
        builder.signatureData = Signatures.SignatureData.newBuilder()
            .setSignerIdentity(
                Signatures.KeyIdentity.newBuilder()
                    .setPublicKey(ByteString.copyFrom(localPublicBytes))
            )
            .setAESGCMPersonalizedData(
                Signatures.AES_GCM_Personalized_Signature_Data.newBuilder()
                    .setEpoch(ByteString.copyFrom(epochCopy))
                    .setNonce(ByteString.copyFrom(nonce))
                    .setCounter(counterValue.toInt())
                    .setExpiresAt(expiresAt.toInt())
                    .setTag(ByteString.copyFrom(tag))
            )
            .build()
    }

    /** HMAC authentication (plaintext payload). Used for Fleet API HTTP commands. */
    fun hmacAuthorize(builder: UniversalMessage.RoutableMessage.Builder, lifetimeSeconds: Long) {
        val plaintext = builder.protobufMessageAsBytes.toByteArray()
        val counterValue: Long
        val epochCopy: ByteArray
        val expiresAt: Long
        synchronized(lock) {
            counter += 1
            counterValue = counter
            epochCopy = epoch.copyOf()
            expiresAt = (System.currentTimeMillis() - vehicleTimeZeroWallMs) / 1000 + lifetimeSeconds
        }

        val tag = hmacMetadata(messageAuthKey, plaintext) {
            add(TagDefs.SIGNATURE_TYPE, byteArrayOf(TagDefs.SIG_TYPE_HMAC_PERSONALIZED.toByte()))
            add(TagDefs.DOMAIN, byteArrayOf(builder.toDestination.domain.number.toByte()))
            add(TagDefs.PERSONALIZATION, vin.encodeToByteArray())
            add(TagDefs.EPOCH, epochCopy)
            addUint32(TagDefs.EXPIRES_AT, expiresAt)
            addUint32(TagDefs.COUNTER, counterValue)
            if (builder.flags != 0) addUint32(TagDefs.FLAGS, builder.flags.toLong())
        }

        builder.signatureData = Signatures.SignatureData.newBuilder()
            .setSignerIdentity(
                Signatures.KeyIdentity.newBuilder()
                    .setPublicKey(ByteString.copyFrom(localPublicBytes))
            )
            .setHMACPersonalizedData(
                Signatures.HMAC_Personalized_Signature_Data.newBuilder()
                    .setEpoch(ByteString.copyFrom(epochCopy))
                    .setCounter(counterValue.toInt())
                    .setExpiresAt(expiresAt.toInt())
                    .setTag(ByteString.copyFrom(tag))
            )
            .build()
    }

    /**
     * Decrypt an encrypted vehicle response.
     * Returns (response counter for anti-replay, plaintext payload).
     * Port of Signer.Decrypt + Peer.responseMetadata.
     */
    fun decryptResponse(message: UniversalMessage.RoutableMessage, requestHash: ByteArray): Pair<Long, ByteArray> {
        val gcm = message.signatureData.getAESGCMResponseData()
        val aad = sha256Metadata {
            add(TagDefs.SIGNATURE_TYPE, byteArrayOf(TagDefs.SIG_TYPE_AES_GCM_RESPONSE.toByte()))
            add(TagDefs.DOMAIN, byteArrayOf(message.fromDestination.domain.number.toByte()))
            add(TagDefs.PERSONALIZATION, vin.encodeToByteArray())
            addUint32(TagDefs.COUNTER, gcm.counter.toLong() and 0xFFFFFFFFL)
            addUint32(TagDefs.FLAGS, message.flags.toLong())
            add(TagDefs.REQUEST_HASH, requestHash)
            addUint32(TagDefs.FAULT, message.signedMessageStatus.signedMessageFaultValue.toLong())
        }
        val plaintext = TeslaCrypto.gcmOpen(
            keyK,
            gcm.nonce.toByteArray(),
            message.protobufMessageAsBytes.toByteArray(),
            gcm.tag.toByteArray(),
            aad,
        )
        return Pair(gcm.counter.toLong() and 0xFFFFFFFFL, plaintext)
    }

    /** Export session state for the cache (counter, domain public key, epoch, clock). */
    fun export(): ByteArray {
        val clock: Long
        val counterValue: Long
        val epochCopy: ByteArray
        synchronized(lock) {
            clock = vehicleTimestampSeconds
            counterValue = counter
            epochCopy = epoch.copyOf()
        }
        return Signatures.SessionInfo.newBuilder()
            .setCounter(counterValue.toInt())
            .setPublicKey(ByteString.copyFrom(verifierPublicBytes))
            .setEpoch(ByteString.copyFrom(epochCopy))
            .setClockTime(clock.toInt())
            .build()
            .toByteArray()
    }

    /**
     * Request-hash identifying an authenticated request: one byte of auth method
     * + the request tag. Used to authenticate response decryption.
     * Port of authentication.RequestID.
     */
    fun requestHashOf(message: UniversalMessage.RoutableMessage): ByteArray? {
        val sig = message.signatureData
        return when {
            sig.hasAESGCMPersonalizedData() -> byteArrayOf(TagDefs.SIG_TYPE_AES_GCM_PERSONALIZED.toByte()) +
                sig.getAESGCMPersonalizedData().tag.toByteArray()
            sig.hasHMACPersonalizedData() -> {
                var tag = sig.getHMACPersonalizedData().tag.toByteArray()
                if (message.toDestination.domain == UniversalMessage.Domain.DOMAIN_VEHICLE_SECURITY) {
                    tag = tag.copyOf(16)
                }
                byteArrayOf(TagDefs.SIG_TYPE_HMAC_PERSONALIZED.toByte()) + tag
            }
            else -> null
        }
    }
}

/** Tag / signature-type constants from signatures.proto. */
object TagDefs {
    const val SIGNATURE_TYPE = 0
    const val DOMAIN = 1
    const val PERSONALIZATION = 2
    const val EPOCH = 3
    const val EXPIRES_AT = 4
    const val COUNTER = 5
    const val CHALLENGE = 6
    const val FLAGS = 7
    const val REQUEST_HASH = 8
    const val FAULT = 9
    const val END = 255

    const val SIG_TYPE_AES_GCM = 0
    const val SIG_TYPE_AES_GCM_PERSONALIZED = 5
    const val SIG_TYPE_HMAC = 6
    const val SIG_TYPE_HMAC_PERSONALIZED = 8
    const val SIG_TYPE_AES_GCM_RESPONSE = 9
}
