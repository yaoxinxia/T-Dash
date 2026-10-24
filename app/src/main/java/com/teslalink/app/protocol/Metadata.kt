package com.teslalink.app.protocol

import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Port of vehicle-command internal/authentication/metadata.go.
 *
 * Metadata items are serialized as tag-length-value, sorted by ascending tag,
 * terminated by a 0xFF byte.
 */
class Metadata(private val sink: (ByteArray) -> Unit) {
    private val fields = mutableSetOf<Int>()
    private var last = -1

    fun add(tag: Int, value: ByteArray?) {
        if (value == null) return
        require(tag >= last) { "metadata items must be added in increasing tag order" }
        require(value.size <= 255) { "metadata fields can't be more than 255 bytes long" }
        last = tag
        fields.add(tag)
        sink(byteArrayOf(tag.toByte()))
        sink(byteArrayOf(value.size.toByte()))
        sink(value)
    }

    fun addUint32(tag: Int, value: Long) {
        val buf = ByteBuffer.allocate(4)
        buf.putInt(value.toInt())
        add(tag, buf.array())
    }

    fun contains(vararg tags: Int): Boolean = tags.all { it in fields }
}

/** AAD = SHA256(TLV... || 0xFF), used for AES-GCM command encryption. */
fun sha256Metadata(block: Metadata.() -> Unit): ByteArray {
    val md = MessageDigest.getInstance("SHA-256")
    val meta = Metadata(md::update)
    meta.block()
    md.update(byteArrayOf(0xFF.toByte()))
    return md.digest()
}

/** HMAC-SHA256 keyed metadata hash: HMAC(key, TLV... || 0xFF || message). */
fun hmacMetadata(key: ByteArray, message: ByteArray = ByteArray(0), block: Metadata.() -> Unit): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    val meta = Metadata(mac::update)
    meta.block()
    mac.update(byteArrayOf(0xFF.toByte()))
    mac.update(message)
    return mac.doFinal()
}
