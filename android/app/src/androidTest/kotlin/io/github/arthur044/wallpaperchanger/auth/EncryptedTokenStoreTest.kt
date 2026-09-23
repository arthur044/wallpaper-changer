package io.github.arthur044.wallpaperchanger.auth

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

// Runs on a real device: the Android Keystore can't be faked on the JVM.
@RunWith(AndroidJUnit4::class)
class EncryptedTokenStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val file = File(context.cacheDir, "test_auth_state.bin")
    private val secret = """{"refreshToken":"AQD-very-secret-refresh-token","scope":"x"}"""

    private fun newStore() = EncryptedTokenStore(
        context = context,
        file = file,
        keysetPrefsName = "test_tink_prefs",
        masterKeyAlias = "test_auth_master_key",
    )

    @Before
    @After
    fun cleanUp() {
        file.delete()
        File(file.path + ".new").delete()
        File(file.path + ".bak").delete()
    }

    @Test
    fun nothingStoredYieldsNull() {
        assertNull(newStore().load())
    }

    @Test
    fun savedValueSurvivesANewInstance() {
        newStore().save(secret)
        assertEquals(secret, newStore().load())
    }

    @Test
    fun plaintextNeverReachesDisk() {
        newStore().save(secret)
        assertFalse(file.readBytes().decodeToString().contains("very-secret"))
    }

    @Test
    fun clearForgetsTheValue() {
        val store = newStore()
        store.save(secret)
        store.clear()
        assertNull(store.load())
        assertFalse(file.exists())
    }

    @Test
    fun tamperedFileIsTreatedAsSignedOut() {
        newStore().save(secret)
        val bytes = file.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x01).toByte()
        file.writeBytes(bytes)

        assertNull(newStore().load())
        assertFalse("undecryptable file should be discarded", file.exists())
    }
}
