package io.relay.app

import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/**
 * A stand-in for a Windows install: it makes a P-256 key, and signs a challenge
 * the way the PC does -- as the 64-byte `r ‖ s`, not DER (/shared/pairing-beacon.md
 * → "Trusted computers").
 *
 * Deliberately independent of [io.relay.app.core.TrustProof], so a test that
 * signs with this and verifies with that is two implementations agreeing, not
 * one agreeing with itself. The shared vectors are the third opinion.
 */
class TestComputer {
    private val pair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()
    private val private: PrivateKey = pair.private

    /** `0x04 ‖ X ‖ Y`, 65 bytes. */
    val key: ByteArray = (pair.public as ECPublicKey).w.let { w ->
        byteArrayOf(0x04) + fixed(w.affineX) + fixed(w.affineY)
    }

    fun sign(challenge: ByteArray): ByteArray {
        val message = "relay-trust-v1\n".toByteArray(Charsets.US_ASCII) + challenge + key
        val der = Signature.getInstance("SHA256withECDSA").run {
            initSign(private)
            update(message)
            sign()
        }
        return derToP1363(der)
    }

    private companion object {
        fun fixed(value: BigInteger): ByteArray {
            val raw = value.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
            return ByteArray(32 - raw.size) + raw
        }

        /** SEQUENCE { INTEGER r, INTEGER s } to `r ‖ s`. */
        fun derToP1363(der: ByteArray): ByteArray {
            var i = 2
            if (der[1].toInt() and 0x80 != 0) i += der[1].toInt() and 0x7f
            fun integer(): ByteArray {
                check(der[i] == 0x02.toByte())
                val length = der[i + 1].toInt()
                val bytes = der.copyOfRange(i + 2, i + 2 + length)
                i += 2 + length
                return fixed(BigInteger(1, bytes))
            }
            return integer() + integer()
        }
    }
}
