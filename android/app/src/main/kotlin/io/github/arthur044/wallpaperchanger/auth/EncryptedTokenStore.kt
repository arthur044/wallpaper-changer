package io.github.arthur044.wallpaperchanger.auth

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.GeneralSecurityException

/**
 * Holds one secret string (the serialized AppAuth state) encrypted at rest with
 * AES-256-GCM. The data key lives in a Tink keyset that is itself wrapped by a
 * non-exportable Android Keystore key: the Credential Manager of the desktop app.
 *
 * Blocking I/O and Keystore calls: use from a background dispatcher.
 */
class EncryptedTokenStore(
    context: Context,
    private val file: File = File(context.filesDir, "spotify_auth_state.bin"),
    private val keysetPrefsName: String = "wc_tink_prefs",
    private val masterKeyAlias: String = "wc_auth_master_key",
) {
    private val appContext = context.applicationContext
    private val atomicFile = AtomicFile(file)

    // Keystore setup is slow, so it waits for the first real read/write.
    private val aead: Aead by lazy {
        AeadConfig.register()
        AndroidKeysetManager.Builder()
            .withSharedPref(appContext, KEYSET_NAME, keysetPrefsName)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri("android-keystore://$masterKeyAlias")
            .build()
            .keysetHandle
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    /**
     * The stored value, or null if nothing is stored. A file that no longer
     * decrypts (tampered, or restored from a backup onto a device whose Keystore
     * never had the key) is discarded and reported as signed out.
     */
    fun load(): String? {
        val sealed = try {
            atomicFile.readFully()
        } catch (_: FileNotFoundException) {
            return null
        }
        return try {
            aead.decrypt(sealed, ASSOCIATED_DATA).decodeToString()
        } catch (e: GeneralSecurityException) {
            Log.w(TAG, "Stored Spotify session no longer decrypts; discarding it", e)
            clear()
            null
        }
    }

    fun save(value: String) {
        val sealed = aead.encrypt(value.encodeToByteArray(), ASSOCIATED_DATA)
        val out = atomicFile.startWrite()
        try {
            out.write(sealed)
            atomicFile.finishWrite(out)
        } catch (e: IOException) {
            atomicFile.failWrite(out)
            throw e
        }
    }

    fun clear() {
        atomicFile.delete()
    }

    private companion object {
        const val TAG = "EncryptedTokenStore"
        const val KEYSET_NAME = "spotify_auth_keyset"
        // Binds the ciphertext to its purpose, so it can't be swapped for another blob.
        val ASSOCIATED_DATA = "spotify_auth_state".encodeToByteArray()
    }
}
