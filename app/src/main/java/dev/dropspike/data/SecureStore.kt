package dev.dropspike.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts secrets (the Twitch sign-in and integrity tokens) with an AES key held in the
 * Android Keystore. The key never leaves secure hardware and can't be exported, so a copy of
 * the app's files alone doesn't reveal the tokens.
 */
object SecureStore {
    private const val ALIAS = "pocketdrop-secrets"
    private const val TRANSFORM = "AES/GCM/NoPadding"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    /** "iv.ciphertext", both base64. */
    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val body = cipher.doFinal(plain.toByteArray())
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + "." + Base64.encodeToString(body, Base64.NO_WRAP)
    }

    /** Null if the value can't be decrypted (e.g. the key was wiped); the caller treats it as signed out. */
    fun decrypt(stored: String): String? = runCatching {
        val (iv, body) = stored.split('.', limit = 2)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        String(cipher.doFinal(Base64.decode(body, Base64.NO_WRAP)))
    }.onFailure { DiagLog.w("secure store: decrypt failed: ${it.javaClass.simpleName}") }.getOrNull()
}
