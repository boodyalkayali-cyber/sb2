package com.screenbridge

import android.util.Base64
import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

class AdbKey(dir: File) {
    private val priv: PrivateKey
    private val pub: RSAPublicKey

    init {
        val pf = File(dir, "adbkey.priv")
        val uf = File(dir, "adbkey.pub")
        val kf = KeyFactory.getInstance("RSA")
        if (pf.exists() && uf.exists()) {
            priv = kf.generatePrivate(PKCS8EncodedKeySpec(pf.readBytes()))
            pub = kf.generatePublic(X509EncodedKeySpec(uf.readBytes())) as RSAPublicKey
        } else {
            val g = KeyPairGenerator.getInstance("RSA")
            g.initialize(2048)
            val kp = g.generateKeyPair()
            pf.writeBytes(kp.private.encoded)
            uf.writeBytes(kp.public.encoded)
            priv = kp.private
            pub = kp.public as RSAPublicKey
        }
    }

    private val sha1Prefix = byteArrayOf(
        0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
    )

    fun sign(token: ByteArray): ByteArray {
        val s = Signature.getInstance("NONEwithRSA")
        s.initSign(priv)
        s.update(sha1Prefix + token)
        return s.sign()
    }

    private fun le(x: BigInteger, size: Int): ByteArray {
        val b = x.toByteArray()
        val out = ByteArray(size)
        for (i in 0 until size) {
            val idx = b.size - 1 - i
            out[i] = if (idx >= 0) b[idx] else 0
        }
        return out
    }

    /** صيغة المفتاح العام الخاصة بـ Android ADB */
    fun publicKeyBlob(): ByteArray {
        val n = pub.modulus
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = r32.subtract(n.mod(r32).modInverse(r32)).toLong().toInt()
        val rr = BigInteger.ONE.shiftLeft(4096).mod(n)
        val bb = ByteBuffer.allocate(4 + 4 + 256 + 256 + 4).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(64).putInt(n0inv).put(le(n, 256)).put(le(rr, 256)).putInt(65537)
        val b64 = Base64.encode(bb.array(), Base64.NO_WRAP)
        return b64 + " screenbridge@android\u0000".toByteArray()
    }
}
