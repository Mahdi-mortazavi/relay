package io.relay.app.core

import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECFieldFp
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.EllipticCurve
import java.util.Base64

/**
 * What a PC has to prove before the phone will stop asking about it.
 *
 * A PC is identified by a P-256 key it made once and kept; the phone sends it a
 * random challenge and the PC signs it. Possession of the private key is the
 * only thing recognised -- not a name and not an address, both of which anyone
 * on the network can type. Contract: /shared/pairing-beacon.md → "Trusted
 * computers"; ADR-0012. The vectors in /shared/test-vectors.json →
 * `trustedComputers` pin every byte of this, and Windows asserts the same ones.
 *
 * Plain `java.security` and nothing else, on purpose. `minSdk` is 26 and
 * Ed25519 in the platform arrives in API 33; ECDSA on P-256 is in every Android
 * release and in .NET. It also has no Android imports, so it is reachable from a
 * JVM test -- a rule this consequential should not be unverifiable until it
 * reaches hardware.
 */
object TrustProof {
    /** Domain separation: a signature made for anything else cannot be replayed here. */
    const val DOMAIN = "relay-trust-v1\n"

    /** `0x04 ‖ X ‖ Y`, an uncompressed P-256 point. */
    const val KEY_BYTES = 65
    const val CHALLENGE_BYTES = 32

    /** IEEE P1363: `r ‖ s`, 32 bytes each. Not DER. */
    const val PROOF_BYTES = 64

    // NIST P-256 / secp256r1. Spelled out rather than fetched from a provider so
    // the on-curve check below does not depend on which provider is installed.
    private val P = BigInteger("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF", 16)
    private val A = P.subtract(BigInteger.valueOf(3))
    private val B = BigInteger("5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B", 16)
    private val N = BigInteger("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16)
    private val GX = BigInteger("6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296", 16)
    private val GY = BigInteger("4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5", 16)

    private val params = ECParameterSpec(
        EllipticCurve(ECFieldFp(P), A, B),
        ECPoint(GX, GY),
        N,
        1,
    )

    /**
     * A key off the wire, or null if it is not exactly an uncompressed P-256
     * point that lies on the curve. Everything here is untrusted input.
     */
    fun decodeKey(base64: String?): ByteArray? {
        val bytes = decode(base64) ?: return null
        return bytes.takeIf { pointOrNull(it) != null }
    }

    /** A label for a person to compare, not a security boundary: `E0EB-C2E6`. */
    fun fingerprint(key: ByteArray): String {
        val hex = MessageDigest.getInstance("SHA-256").digest(key)
            .joinToString("") { "%02X".format(it) }
            .take(8)
        return hex.substring(0, 4) + "-" + hex.substring(4)
    }

    /** 32 bytes, fresh for one connection. */
    fun newChallenge(random: SecureRandom = SecureRandom()): ByteArray =
        ByteArray(CHALLENGE_BYTES).also { random.nextBytes(it) }

    /** The exact bytes the PC signs. */
    fun message(challenge: ByteArray, key: ByteArray): ByteArray =
        DOMAIN.toByteArray(Charsets.US_ASCII) + challenge + key

    /**
     * Whether [proof] is [key]'s signature over [challenge]. False for anything
     * malformed -- wrong lengths, a point off the curve, DER where `r ‖ s` was
     * asked for -- and for anything else that goes wrong. A verifier that can
     * throw is a verifier whose caller has to remember to catch.
     */
    fun verify(key: ByteArray, challenge: ByteArray, proof: ByteArray): Boolean = try {
        if (challenge.size != CHALLENGE_BYTES || proof.size != PROOF_BYTES) {
            false
        } else {
            val point = pointOrNull(key)
            if (point == null) {
                false
            } else {
                val publicKey = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, params))
                val signature = Signature.getInstance("SHA256withECDSA")
                signature.initVerify(publicKey)
                signature.update(message(challenge, key))
                signature.verify(toDer(proof))
            }
        }
    } catch (_: Exception) {
        false
    }

    fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    /** Strict standard base64, or null. Never throws on hostile input. */
    fun decode(text: String?): ByteArray? = try {
        if (text.isNullOrEmpty()) null else Base64.getDecoder().decode(text)
    } catch (_: IllegalArgumentException) {
        null
    }

    /** The point, if [key] is 65 bytes, uncompressed, and on the curve. */
    private fun pointOrNull(key: ByteArray): ECPoint? {
        if (key.size != KEY_BYTES || key[0] != 0x04.toByte()) return null
        val x = BigInteger(1, key.copyOfRange(1, 33))
        val y = BigInteger(1, key.copyOfRange(33, 65))
        if (x >= P || y >= P) return null
        // y² = x³ + ax + b  (mod p)
        val left = y.multiply(y).mod(P)
        val right = x.pow(3).add(A.multiply(x)).add(B).mod(P)
        return if (left == right) ECPoint(x, y) else null
    }

    /** `r ‖ s` to the DER the platform verifier wants. */
    private fun toDer(proof: ByteArray): ByteArray {
        val r = integer(proof.copyOfRange(0, 32))
        val s = integer(proof.copyOfRange(32, 64))
        val body = r + s
        return byteArrayOf(0x30, body.size.toByte()) + body
    }

    private fun integer(unsigned: ByteArray): ByteArray {
        var start = 0
        while (start < unsigned.size - 1 && unsigned[start] == 0.toByte()) start++
        var value = unsigned.copyOfRange(start, unsigned.size)
        // A leading 1 bit would read as negative in DER.
        if (value[0] < 0) value = byteArrayOf(0) + value
        return byteArrayOf(0x02, value.size.toByte()) + value
    }
}
