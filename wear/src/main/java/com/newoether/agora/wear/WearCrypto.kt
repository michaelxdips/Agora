package com.newoether.agora.wear

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM encryption with the key held in the Android keystore.
 *
 * Scope, stated honestly: this protects the API key **at rest** on the watch (a lost watch's
 * `filesDir` is not readable without the keystore entry, which is hardware-backed on the target
 * device). It does not protect against a rooted watch with a live keystore, and it is not a
 * substitute for the key being revocable — the user can always re-issue the key.
 *
 * `ponytail:` no key rotation and no biometric binding. Both are additive when the threat model
 * grows past "lost watch"; rotation would be a second key version in the payload header.
 *
 * Maintainer: Michael — this file belongs to the Hermes fork of Agora (see NOTICE.md).
 */
object WearCrypto {

    private const val TAG = "HermesWearConfig"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "hermes_wear_config_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    fun encrypt(context: Context, plain: String): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        // iv || ciphertext — the IV is not secret, and GCM needs it to decrypt.
        return iv + body
    }

    fun decrypt(context: Context, payload: ByteArray): String? {
        if (payload.size <= IV_BYTES) return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(GCM_TAG_BITS, payload, 0, IV_BYTES),
            )
            String(cipher.doFinal(payload, IV_BYTES, payload.size - IV_BYTES), Charsets.UTF_8)
        }.onFailure {
            // A GCM failure here means the ciphertext or the key changed — never fall back to
            // plaintext, never log the payload.
            Log.w(TAG, "config decrypt failed: ${it.javaClass.simpleName}")
        }.getOrNull()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    /** Base64 helper for the Data Layer payload, which is a `DataMap` of strings. */
    fun encode(payload: ByteArray): String = Base64.encodeToString(payload, Base64.NO_WRAP)

    fun decode(value: String): ByteArray? = runCatching { Base64.decode(value, Base64.NO_WRAP) }.getOrNull()
}
