package app.galla.captain

import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import java.security.spec.RSAPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/**
 * Seals one recording exactly like the web app does, so the admin tool opens both:
 * "GKV1" + 4-byte big-endian header length + JSON header + AES-256-GCM ciphertext.
 * The AES key is wrapped with Galla's RSA public key (RSA-OAEP, SHA-256, MGF1-SHA-256).
 * The header carries the SHA-256 of the original recording, taken here at capture time.
 */
object Sealer {
    class Sealed(val bytes: ByteArray, val hash: String, val plainSize: Int)

    private val rng = SecureRandom()
    private var demoKey: PublicKey? = null

    fun keyFor(cfg: JSONObject?): PublicKey {
        val jwk = cfg?.optJSONObject("publicKey")
        if (jwk != null && jwk.has("n") && jwk.has("e")) return fromJwk(jwk)
        // Demo: a throwaway key whose private half is never kept, so demo recordings can't be opened.
        return demoKey ?: KeyPairGenerator.getInstance("RSA").apply { initialize(3072) }.generateKeyPair().public.also { demoKey = it }
    }

    private fun fromJwk(jwk: JSONObject): PublicKey {
        val flags = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        val n = BigInteger(1, Base64.decode(jwk.getString("n"), flags))
        val e = BigInteger(1, Base64.decode(jwk.getString("e"), flags))
        return KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(n, e))
    }

    fun sha256Hex(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)

    fun seal(plain: ByteArray, head: JSONObject, pub: PublicKey): Sealed {
        val hash = sha256Hex(plain)
        val aes = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val iv = ByteArray(12).also { rng.nextBytes(it) }
        val gcm = Cipher.getInstance("AES/GCM/NoPadding")
        gcm.init(Cipher.ENCRYPT_MODE, aes, GCMParameterSpec(128, iv))
        val ct = gcm.doFinal(plain)                       // tag appended, same as WebCrypto
        val rsa = Cipher.getInstance("RSA/ECB/OAEPPadding")
        rsa.init(Cipher.ENCRYPT_MODE, pub, OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT))
        val wrapped = rsa.doFinal(aes.encoded)
        head.put("v", 1).put("hash", hash).put("size", plain.size).put("iv", b64(iv)).put("key", b64(wrapped))
        val header = head.toString().toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream(8 + header.size + ct.size)
        out.write("GKV1".toByteArray(Charsets.US_ASCII))
        out.write(ByteBuffer.allocate(4).putInt(header.size).array())
        out.write(header)
        out.write(ct)
        return Sealed(out.toByteArray(), hash, plain.size)
    }
}
