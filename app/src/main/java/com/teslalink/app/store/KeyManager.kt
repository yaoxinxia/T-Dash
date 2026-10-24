package com.teslalink.app.store

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.teslalink.app.protocol.TeslaCrypto
import java.security.KeyPair
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Generates (once) and stores the P-256 key pair used as a vehicle key.
 * The private key (PKCS#8 DER) is encrypted at rest with an AndroidKeyStore AES key.
 */
class KeyManager(private val context: Context) {

    private val prefs = PrefsStore(context)

    @Volatile private var cached: KeyPair? = null
    @Volatile private var cachedPublicBytes: ByteArray? = null

    suspend fun getOrCreateKeyPair(): KeyPair {
        cached?.let { return it }
        val privB64 = prefs.getString(KEY_PRIV)
        val pubB64 = prefs.getString(KEY_PUB)
        if (privB64 != null && pubB64 != null) {
            // Base64.decode 对坏数据直接抛 IllegalArgumentException。它以前裸在外面：DataStore 里
            // 那串被写坏/截断（备份恢复、磁盘异常）时，app 一读钥匙就闪退。现在降级成空数组，
            // 下面的解封循环会自然判定"格式异常"并走既不存在的提示路径——绝不自动换钥匙。
            val blob = runCatching { Base64.decode(privB64, Base64.NO_WRAP) }.getOrNull() ?: ByteArray(0)
            val pub = runCatching { Base64.decode(pubB64, Base64.NO_WRAP) }.getOrNull() ?: ByteArray(0)
            if (blob.isEmpty() || pub.isEmpty()) {
                com.teslalink.app.debug.DebugLog.error("key", "存下来的钥匙不是合法 Base64（数据被写坏），需要重新配对")
            }
            val restored = runCatching {
                // The stored blob is AndroidKeyStore-GCM: iv || ciphertext+tag. It MUST be unsealed
                // first — feeding it to PKCS8EncodedKeySpec fails, and silently generating a new key
                // would leave the vehicle holding the old public key (every command then fails as
                // "unknown key" while unauthenticated reads still work). AndroidKeyStore uses 12-byte
                // GCM IVs; 16 is kept as a fallback so a different IV size can't brick the key.
                var lastFailure: Throwable? = null
                for (ivLen in IV_SIZES) {
                    if (blob.size <= ivLen) continue
                    try {
                        val pkcs8 = keystoreOpen(
                            Sealed(blob.copyOfRange(0, ivLen), blob.copyOfRange(ivLen, blob.size))
                        )
                        val pair = TeslaCrypto.restoreKeyPair(pkcs8, pub)
                        if (pair != null) {
                            if (ivLen != IV_LENGTH) {
                                com.teslalink.app.debug.DebugLog.log(
                                    "key",
                                    "钥匙解密使用 iv=$ivLen 字节（预期 $IV_LENGTH）"
                                )
                            }
                            return@runCatching pair
                        }
                        lastFailure = IllegalStateException("密钥对解析失败")
                    } catch (e: Exception) {
                        lastFailure = e
                    }
                }
                throw (lastFailure ?: IllegalStateException("钥匙数据格式异常 ${blob.size} 字节"))
            }
            val pair = restored.getOrNull()
            if (pair != null) {
                cached = pair
                cachedPublicBytes = pub
                com.teslalink.app.debug.DebugLog.log(
                    "key",
                    "载入本机既有钥匙 指纹=${com.teslalink.app.debug.DebugLog.fp(pub)}"
                )
                return pair
            }
            // Never replace the key on our own: the vehicle's keychain still holds the old public
            // key, so a silent rotation looks exactly like "the car forgot my key".
            com.teslalink.app.debug.DebugLog.error(
                "key",
                "本机钥匙无法解密（指纹 ${com.teslalink.app.debug.DebugLog.fp(pub)}）：" +
                    "${restored.exceptionOrNull()?.message} —— 需要用户在「设置」页显式重新生成并重新配对"
            )
            throw IllegalStateException(
                "本机钥匙无法解密（AndroidKeyStore 主密钥已丢失）。车辆记录的仍是旧公钥 " +
                    com.teslalink.app.debug.DebugLog.fp(pub) +
                    "，自动重试不会成功。请到「设置」页点「重新生成本机钥匙」，然后重新刷卡配对。"
            )
        }
        if ((privB64 == null) != (pubB64 == null)) {
            // Half-written pair (killed mid-write, partial restore, one key cleared by hand):
            // generating a replacement here would silently diverge from the vehicle's keychain.
            com.teslalink.app.debug.DebugLog.error("key", "本机钥匙数据不完整（私钥/公钥缺一），拒绝自动生成")
            throw IllegalStateException(
                "本机钥匙数据不完整（只剩公钥或只剩私钥）。请到「设置」页点「重新生成本机钥匙」，" +
                    "然后重新刷卡配对，并在车机钥匙列表删除旧条目。"
            )
        }
        val pair = generateAndStore(firstRun = privB64 == null)
        return pair
    }

    /**
     * Generates, verifies and atomically persists a new key pair. Never called just because the
     * stored key failed to open (see getOrCreateKeyPair) — a silent rotation is indistinguishable
     * from the vehicle forgetting the key.
     */
    private suspend fun generateAndStore(firstRun: Boolean): KeyPair {
        val pair = TeslaCrypto.generateKeyPair()
        val pkcs8 = pair.private.encoded
        val pubBytes = TeslaCrypto.publicBytes(pair.public)
        val sealed = keystoreSeal(pkcs8)
        // Prove the sealed blob can be opened again BEFORE persisting it: a keystore quirk that
        // only shows up on the next start would look exactly like "the car lost my key".
        val roundTrip = runCatching { keystoreOpen(sealed).contentEquals(pkcs8) }
        if (roundTrip.getOrDefault(false) != true) {
            com.teslalink.app.debug.DebugLog.error(
                "key",
                "钥匙密封自检失败，未写入存储：${roundTrip.exceptionOrNull()?.message}"
            )
            throw IllegalStateException(
                "本机钥匙存储自检失败（AndroidKeyStore）：" +
                    (roundTrip.exceptionOrNull()?.message ?: "回读不一致") +
                    "，已保持原钥匙不变。"
            )
        }
        prefs.putStrings(
            mapOf(
                KEY_PRIV to Base64.encodeToString(sealed.iv + sealed.ciphertext, Base64.NO_WRAP),
                KEY_PUB to Base64.encodeToString(pubBytes, Base64.NO_WRAP),
            )
        )
        cached = pair
        cachedPublicBytes = pubBytes
        com.teslalink.app.debug.DebugLog.log(
            "key",
            "生成新钥匙 指纹=${com.teslalink.app.debug.DebugLog.fp(pubBytes)}" +
                if (firstRun) "（首次运行，需要到车辆处配对一次）" else "（按用户要求替换）"
        )
        return pair
    }

    /**
     * Explicitly replaces the local key pair. Only ever called from user action: the vehicle must
     * be re-paired afterwards, and the stale entry should be deleted on the car's key screen.
     */
    suspend fun rotateKey(): ByteArray {
        generateAndStore(firstRun = false)
        // 不用 !!：路径被重构时它会给一个裸 NPE，换成说得出原因的异常。
        return cachedPublicBytes ?: error("钥匙轮换后没拿到公钥（内部状态异常）")
    }

    suspend fun publicBytes(): ByteArray {
        cachedPublicBytes?.let { return it }
        getOrCreateKeyPair()
        return cachedPublicBytes ?: error("本机还没有钥匙，需要先在设置页配对")
    }

    data class Sealed(val iv: ByteArray, val ciphertext: ByteArray)

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun keystoreSeal(plaintext: ByteArray): Sealed {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        val out = cipher.doFinal(plaintext)
        return Sealed(cipher.iv, out)
    }

    private fun keystoreOpen(sealed: Sealed): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, sealed.iv))
        return cipher.doFinal(sealed.ciphertext)
    }

    companion object {
        private const val KEYSTORE_ALIAS = "teslalink_master"
        private const val KEY_PRIV = "vehicle_key_priv"
        private const val KEY_PUB = "vehicle_key_pub"

        /** AndroidKeyStore AES-GCM generates 12-byte IVs; we store iv || ciphertext. */
        private const val IV_LENGTH = 12

        /** IV sizes to try when unsealing, so an unexpected IV length can't silently destroy the key. */
        private val IV_SIZES = intArrayOf(IV_LENGTH, 16)
    }
}
