package com.skycop.sim

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** AES-256-GCM sealed as base64(iv || ciphertext || tag), the layout WebCrypto produces. */
internal object PairingCrypto {
    private val random = SecureRandom()

    fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun key(hex: String) = SecretKeySpec(ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }, "AES")

    fun seal(keyHex: String, plain: String): String {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(keyHex), GCMParameterSpec(128, iv))
        return Base64.encodeToString(iv + cipher.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    /** The same as [open] for binary content, such as a relayed video frame. */
    fun openBytes(keyHex: String, sealed: String): ByteArray? = try {
        val bytes = Base64.decode(sealed, Base64.DEFAULT)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(keyHex), GCMParameterSpec(128, bytes, 0, 12))
        cipher.doFinal(bytes, 12, bytes.size - 12)
    } catch (error: Exception) {
        null
    }

    fun open(keyHex: String, sealed: String): String? = try {
        val bytes = Base64.decode(sealed, Base64.DEFAULT)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(keyHex), GCMParameterSpec(128, bytes, 0, 12))
        String(cipher.doFinal(bytes, 12, bytes.size - 12))
    } catch (error: Exception) {
        null
    }
}
