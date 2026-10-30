package com.couchtasks.android.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class CertificatePasswordStore(private val context: Context, private val profileId: String = "legacy") {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun forProfile(id: String) = CertificatePasswordStore(context, id)

    fun hasPassword(): Boolean = preferences.contains(KEY_PASSWORD_CIPHERTEXT) && preferences.contains(KEY_PASSWORD_IV)

    fun savePassword(password: CharArray) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(password.concatToString().toByteArray(Charsets.UTF_8))
        val editor = preferences.edit().apply {
            putString(KEY_PASSWORD_CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            putString(KEY_PASSWORD_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
        }
        check(editor.commit()) { "Could not persist encrypted certificate password" }
    }

    fun loadPassword(): CharArray? {
        val ciphertext = preferences.getString(KEY_PASSWORD_CIPHERTEXT, null) ?: return null
        val iv = preferences.getString(KEY_PASSWORD_IV, null) ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(GCM_TAG_BITS, Base64.decode(iv, Base64.NO_WRAP)),
        )
        return cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP))
            .toString(Charsets.UTF_8)
            .toCharArray()
    }

    fun clearPassword() {
        preferences.edit(commit = true) {
            remove(KEY_PASSWORD_CIPHERTEXT)
            remove(KEY_PASSWORD_IV)
        }
    }

    fun clearUnusedProfiles(retained: Set<String>) {
        preferences.edit(commit = true) {
            preferences.all.keys.forEach { key ->
                val owner = when {
                    key.startsWith("client_p12_password_") -> "legacy"
                    key.endsWith("_ciphertext") -> key.removeSuffix("_ciphertext")
                    key.endsWith("_iv") -> key.removeSuffix("_iv")
                    else -> return@forEach
                }
                if (owner !in retained) remove(key)
            }
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val builder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setUnlockedDeviceRequired(false)
        keyGenerator.init(builder.build())
        return keyGenerator.generateKey()
    }

    private val KEY_PASSWORD_CIPHERTEXT get() = if (profileId == "legacy") "client_p12_password_ciphertext" else "${profileId}_ciphertext"
    private val KEY_PASSWORD_IV get() = if (profileId == "legacy") "client_p12_password_iv" else "${profileId}_iv"

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "couch_tasks_client_certificate_password"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val PREFERENCES_NAME = "certificate_password"
    }
}
