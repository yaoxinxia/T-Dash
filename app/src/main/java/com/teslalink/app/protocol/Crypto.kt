package com.teslalink.app.protocol

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Crypto primitives for the Tesla Vehicle Command protocol.
 * Port of vehicle-command internal/authentication/{native,crypto}.go.
 */
object TeslaCrypto {
    const val SHARED_KEY_SIZE_BYTES = 16
    const val GCM_TAG_BITS = 128
    const val GCM_NONCE_BYTES = 12

    private val rng = SecureRandom()

    /** P-256 parameters, used to import raw curve points. */
    val p256Params: ECParameterSpec by lazy {
        val kf = KeyFactory.getInstance("EC")
        val algParams = java.security.AlgorithmParameters.getInstance("EC")
        algParams.init(ECGenParameterSpec("secp256r1"))
        algParams.getParameterSpec(ECParameterSpec::class.java)
    }

    fun generateKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"), rng)
        return kpg.generateKeyPair()
    }

    fun restoreKeyPair(pkcs8Der: ByteArray, rawPublic: ByteArray): KeyPair? {
        return try {
            val kf = KeyFactory.getInstance("EC")
            val priv = kf.generatePrivate(PKCS8EncodedKeySpec(pkcs8Der))
            val point = decodePoint(rawPublic) ?: return null
            val pub = kf.generatePublic(ECPublicKeySpec(point, p256Params))
            KeyPair(pub, priv)
        } catch (e: Exception) {
            null
        }
    }

    /** 65-byte uncompressed encoding: 0x04 || X(32) || Y(32). */
    fun publicBytes(publicKey: PublicKey): ByteArray {
        // as? 而不是 as：传进来不是 EC 公钥时要说清"哪一步喂错了"，而不是抛裸 ClassCastException。
        val w = (publicKey as? ECPublicKey)?.w
            ?: throw IllegalArgumentException("需要 EC 公钥，实际拿到 ${publicKey.algorithm}（${publicKey.javaClass.simpleName}）")
        val out = ByteArray(65)
        out[0] = 0x04
        leftPad(w.affineX, out, 1, 32)
        leftPad(w.affineY, out, 33, 32)
        return out
    }

    private fun leftPad(v: BigInteger, out: ByteArray, off: Int, len: Int) {
        val bytes = v.toByteArray() // may include a leading zero for sign
        val trimmed = if (bytes.size > len && bytes[0].toInt() == 0) bytes.copyOfRange(1, bytes.size) else bytes
        require(trimmed.size <= len) { "coordinate too long" }
        System.arraycopy(trimmed, 0, out, off + len - trimmed.size, trimmed.size)
    }

    fun decodePoint(bytes: ByteArray): ECPoint? {
        if (bytes.size != 65 || bytes[0] != 0x04.toByte()) return null
        val x = BigInteger(1, bytes.copyOfRange(1, 33))
        val y = BigInteger(1, bytes.copyOfRange(33, 65))
        return ECPoint(x, y)
    }

    /**
     * ECDH shared secret: the 32-byte big-endian X coordinate of c*V.
     * Mirrors NativeECDHKey.sharedSecret (which uses ScalarMult + FillBytes(32)).
     */
    fun ecdhSharedX(privateKey: PrivateKey, peerPublicBytes: ByteArray): ByteArray {
        val point = decodePoint(peerPublicBytes) ?: throw IllegalArgumentException("invalid public key")
        val kf = KeyFactory.getInstance("EC")
        val peerPub = kf.generatePublic(ECPublicKeySpec(point, p256Params))
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(privateKey)
        ka.doPhase(peerPub, true)
        val secret = ka.generateSecret()
        return leftPadTo(secret, 32)
    }

    /** K = SHA1(BIG_ENDIAN(Sx, 32))[:16]. */
    fun sharedKey(sharedX: ByteArray): ByteArray {
        return sha1(sharedX).copyOf(SHARED_KEY_SIZE_BYTES)
    }

    /** Subkey: HMAC-SHA256(K, label). */
    fun subkey(key: ByteArray, label: String): ByteArray = hmacSha256(key, label.encodeToByteArray())

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun sha1(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-1").digest(data)

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }

    /** AES-128-GCM seal. Returns (nonce, ciphertext, tag). */
    fun gcmSeal(key: ByteArray, plaintext: ByteArray, aad: ByteArray): Triple<ByteArray, ByteArray, ByteArray> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val nonce = randomBytes(GCM_NONCE_BYTES)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(aad)
        val out = cipher.doFinal(plaintext)
        val ct = out.copyOfRange(0, out.size - GCM_TAG_BITS / 8)
        val tag = out.copyOfRange(out.size - GCM_TAG_BITS / 8, out.size)
        return Triple(nonce, ct, tag)
    }

    /** AES-128-GCM open. Throws on authentication failure. */
    fun gcmOpen(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, tag: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext + tag)
    }

    fun leftPadTo(bytes: ByteArray, size: Int): ByteArray {
        require(bytes.size <= size) { "value too long" }
        if (bytes.size == size) return bytes
        return ByteArray(size - bytes.size) + bytes
    }

    /** Constant-time equality. */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        return MessageDigest.isEqual(a, b)
    }
}
