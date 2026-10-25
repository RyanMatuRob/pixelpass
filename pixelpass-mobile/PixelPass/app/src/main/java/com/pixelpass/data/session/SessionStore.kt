package com.pixelpass.data.session

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.pixelpass.core.PixelPassLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.sessionDataStore by preferencesDataStore(name = "pixelpass_session")

/**
 * Encrypted persistence for the signed-in session.
 *
 * The preference file holds a single AES-256-GCM blob; the key itself lives in
 * the Android Keystore so a pulled file, or a backup of it, is useless.
 */
class SessionStore(private val context: Context) {

    private val gson = Gson()

    val sessionFlow: Flow<UserSession?> = context.sessionDataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences ->
            val blob = preferences[SESSION_BLOB] ?: return@map null
            val plain = SessionCrypto.decrypt(blob)
            if (plain == null) {
                PixelPassLog.e(PixelPassLog.SESSION, "Stored session could not be decrypted")
                return@map null
            }
            runCatching { gson.fromJson(String(plain, Charsets.UTF_8), UserSession::class.java) }
                .onFailure { PixelPassLog.e(PixelPassLog.SESSION, "Stored session is malformed", it) }
                .getOrNull()
        }

    suspend fun load(): UserSession? = sessionFlow.first()

    suspend fun save(session: UserSession): Boolean {
        val plain = gson.toJson(session).toByteArray(Charsets.UTF_8)
        val blob = SessionCrypto.encrypt(plain)
        if (blob == null) {
            PixelPassLog.e(PixelPassLog.SESSION, "Session encryption failed; not persisting")
            return false
        }
        context.sessionDataStore.edit { preferences -> preferences[SESSION_BLOB] = blob }
        PixelPassLog.i(PixelPassLog.SESSION, "Session persisted for user ${session.userId} (${session.role})")
        return true
    }

    suspend fun clear() {
        context.sessionDataStore.edit { preferences -> preferences.remove(SESSION_BLOB) }
        PixelPassLog.i(PixelPassLog.SESSION, "Secure session storage cleared")
    }

    private companion object {
        val SESSION_BLOB = stringPreferencesKey("session_blob")
    }
}

internal object SessionCrypto {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "pixelpass_session_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128
    private const val IV_BYTES = 12

    fun encrypt(plain: ByteArray): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val cipherText = cipher.doFinal(plain)
        Base64.encodeToString(iv + cipherText, Base64.NO_WRAP)
    }.getOrNull()

    fun decrypt(blob: String): ByteArray? = runCatching {
        val bytes = Base64.decode(blob, Base64.NO_WRAP)
        check(bytes.size > IV_BYTES) { "Encrypted session blob is too short" }
        val iv = bytes.copyOfRange(0, IV_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
        cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES)
    }.getOrNull()

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        val key = generator.generateKey()
        PixelPassLog.i(PixelPassLog.SESSION, "Generated session encryption key in the Android Keystore")
        return key
    }
}
