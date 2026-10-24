package com.teslalink.app.protocol

import com.tesla.generated.signatures.Signatures
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.ECPrivateKeySpec

/**
 * Ports internal/authentication/protocol_doc_test.go and the test vectors in
 * pkg/protocol/protocol.md from teslamotors/vehicle-command.
 */
class ProtocolVectorsTest {

    companion object {
        const val VIN = "5YJ30123456789ABC"
        const val CLIENT_SCALAR =
            "2538cdc29a97c19c1e99a637d6cf4f8c970c118b56ede1e6323e6d162c4b30db"
        const val CLIENT_PUBLIC =
            "04b2b6bc68c2da0665ce656815594996c62394edd8bea905fe781a754fe6a845a7" +
                "14330902f225e9269d466e05b349981fda9d85cc23c6fb444aa73b629105dc6e"
        const val VEHICLE_PUBLIC =
            "04c7a1f47138486aa4729971494878d33b1a24e39571f748a6e16c5955b3d877d3" +
                "a6aaa0e955166474af5d32c410f439a2234137ad1bb085fd4e8813c958f11d97"
        const val EXPECTED_K = "1b2fce19967b79db696f909cff89ea9a"
        const val EXPECTED_SESSION_INFO_KEY =
            "fceb679ee7bca756fcd441bf238bf2f338629b41d9eb9c67be1b32c9672ce300"
        const val SESSION_INFO_HEX =
            "0806124104c7a1f47138486aa4729971494878d33b1a24e39571f748a6e16c5955b3d877d3" +
                "a6aaa0e955166474af5d32c410f439a2234137ad1bb085fd4e8813c958f11d97" +
                "1a104c463f9cc0d3d26906e982ed224adde6255a0a0000"
        const val CHALLENGE = "1588d5a30eabc6f8fc9a951b11f6fd11"
        const val EXPECTED_SESSION_INFO_TAG =
            "996c1fe38331be138f8039c194b14db2198846ed7d8251e6749284d7b32ea002"

        fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        fun clientPrivateKey(): PrivateKey {
            val kf = KeyFactory.getInstance("EC")
            val params = TeslaCrypto.p256Params
            return kf.generatePrivate(
                ECPrivateKeySpec(BigInteger(1, hex(CLIENT_SCALAR)), params)
            )
        }
    }

    /** Metadata serialization must match the byte string in protocol.md. */
    @Test
    fun tlvMetadataMatchesDoc() {
        val docMetadata =
            "000105010103021135594a333031323334353637383941424303104c463f9cc0d3d26906e982ed224adde6" +
                "040400000a5f050400000007070400000002ff"
        val aad = sha256Metadata {
            add(TagDefs.SIGNATURE_TYPE, byteArrayOf(TagDefs.SIG_TYPE_AES_GCM_PERSONALIZED.toByte()))
            add(TagDefs.DOMAIN, byteArrayOf(3)) // DOMAIN_INFOTAINMENT
            add(TagDefs.PERSONALIZATION, VIN.encodeToByteArray())
            add(TagDefs.EPOCH, hex("4c463f9cc0d3d26906e982ed224adde6"))
            addUint32(TagDefs.EXPIRES_AT, 2655)
            addUint32(TagDefs.COUNTER, 7)
            addUint32(TagDefs.FLAGS, 1 shl 1)
        }
        val expected = TeslaCrypto.sha256(hex(docMetadata))
        assertArrayEquals(expected, aad)
    }

    /** K = SHA1(ECDH X)[:16], using the doc's client/vehicle test keys. */
    @Test
    fun ecdhDerivesDocKey() {
        val sharedX = TeslaCrypto.ecdhSharedX(clientPrivateKey(), hex(VEHICLE_PUBLIC))
        assertEquals(32, sharedX.size)
        val k = TeslaCrypto.sharedKey(sharedX)
        assertEquals(EXPECTED_K, k.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun sessionInfoSubkeyMatchesDoc() {
        val k = hex(EXPECTED_K)
        val sessionInfoKey = TeslaCrypto.subkey(k, "session info")
        assertEquals(EXPECTED_SESSION_INFO_KEY, sessionInfoKey.joinToString("") { "%02x".format(it) })
    }

    /** Full handshake verification path with the doc's signed session info. */
    @Test
    fun sessionInfoHmacVerifies() {
        val encodedInfo = hex(SESSION_INFO_HEX)
        val tag = hex(EXPECTED_SESSION_INFO_TAG)
        val challenge = hex(CHALLENGE)

        val session = TeslaSession.import(
            clientPrivateKey(),
            hex(CLIENT_PUBLIC),
            VIN,
            encodedInfo,
            cachedAtWallMs = System.currentTimeMillis(),
        )
        assertTrue(session.verifySessionInfoTag(challenge, encodedInfo, tag))
        assertFalse(session.verifySessionInfoTag(challenge, encodedInfo, tag.copyOf().also { it[0] = 0 }))
    }

    /** The session must load counter/epoch/public key from the doc's session info. */
    @Test
    fun sessionStateParsed() {
        val info = Signatures.SessionInfo.parseFrom(hex(SESSION_INFO_HEX))
        assertEquals(6, info.counter)
        assertArrayEquals(hex(VEHICLE_PUBLIC), info.publicKey.toByteArray())
        assertArrayEquals(hex("4c463f9cc0d3d26906e982ed224adde6"), info.epoch.toByteArray())
        assertEquals(2650, info.clockTime)

        val session = TeslaSession.import(
            clientPrivateKey(), hex(CLIENT_PUBLIC), VIN,
            hex(SESSION_INFO_HEX), System.currentTimeMillis(),
        )
        assertArrayEquals(hex(VEHICLE_PUBLIC), session.verifierPublicBytes)
        // First command uses counter+1 (= 7), exactly as in the doc example.
        val builder = com.tesla.generated.universalmessage.UniversalMessage.RoutableMessage.newBuilder()
            .setToDestination(
                com.tesla.generated.universalmessage.UniversalMessage.Destination.newBuilder()
                    .setDomain(com.tesla.generated.universalmessage.UniversalMessage.Domain.DOMAIN_INFOTAINMENT)
            )
            .setProtobufMessageAsBytes(com.google.protobuf.ByteString.copyFrom(hex("120452020801")))
            .setFlags(1 shl 1)
        session.encryptCommand(builder, lifetimeSeconds = 5)
        val sig = builder.build().signatureData
        assertTrue(sig.hasAESGCMPersonalizedData())
        assertEquals(7, sig.getAESGCMPersonalizedData().counter)
    }

    @Test
    fun gcmRoundTrip() {
        val key = hex(EXPECTED_K)
        val plaintext = "hello tesla".encodeToByteArray()
        val aad = TeslaCrypto.sha256("aad".encodeToByteArray())
        val (nonce, ciphertext, tag) = TeslaCrypto.gcmSeal(key, plaintext, aad)
        assertArrayEquals(plaintext, TeslaCrypto.gcmOpen(key, nonce, ciphertext, tag, aad))
    }

    @Test
    fun slidingWindowRejectsReplay() {
        val window = SlidingWindow()
        assertTrue(window.update(10))
        assertFalse(window.update(10)) // same as current counter
        assertTrue(window.update(11)) // advance
        assertFalse(window.update(11)) // same as current counter
        assertTrue(window.update(13)) // skip 12
        assertTrue(window.update(12)) // unseen, within window: accepted out of order
        assertFalse(window.update(12)) // now seen
        assertFalse(window.update(11)) // already marked as used
        assertTrue(window.update(45)) // jump forward
    }

    @Test
    fun requestHashForGcm() {
        val builder = com.tesla.generated.universalmessage.UniversalMessage.RoutableMessage.newBuilder()
            .setToDestination(
                com.tesla.generated.universalmessage.UniversalMessage.Destination.newBuilder()
                    .setDomain(com.tesla.generated.universalmessage.UniversalMessage.Domain.DOMAIN_VEHICLE_SECURITY)
            )
            .setProtobufMessageAsBytes(com.google.protobuf.ByteString.EMPTY)
        val session = TeslaSession.import(
            clientPrivateKey(), hex(CLIENT_PUBLIC), VIN,
            hex(SESSION_INFO_HEX), System.currentTimeMillis(),
        )
        session.encryptCommand(builder, lifetimeSeconds = 5)
        val hash = session.requestHashOf(builder.build())!!
        assertEquals(17, hash.size) // 1 type byte + 16-byte GCM tag
        assertEquals(TagDefs.SIG_TYPE_AES_GCM_PERSONALIZED.toByte(), hash[0])
    }
}
