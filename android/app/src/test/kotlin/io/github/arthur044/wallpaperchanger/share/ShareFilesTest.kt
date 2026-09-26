package io.github.arthur044.wallpaperchanger.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

// Nothing persists for the share image: one file at most, ever.
class ShareFilesTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val dir: File by lazy { File(temp.root, "share") }
    private val files by lazy { ShareFiles(dir) }

    private fun onDisk(): List<File> = dir.listFiles().orEmpty().toList()

    @Test
    fun theNextShareDeletesThePreviousOne() {
        val first = files.replace("jpg") { it.write(1) }
        Thread.sleep(2) // a new name each time comes from the clock
        val second = files.replace("jpg") { it.write(2) }

        assertFalse(first.exists())
        assertEquals(listOf(second), onDisk())
    }

    @Test
    fun aShareOfAnotherFormatStillLeavesOneFile() {
        files.replace("png") { it.write(1) }
        val jpeg = files.replace("jpg") { it.write(2) }

        assertEquals(listOf(jpeg), onDisk())
    }

    @Test
    fun aFailedWriteLeavesNothingBehind() {
        files.replace("jpg") { it.write(1) }

        val thrown = runCatching { files.replace("jpg") { throw IOException("disk full") } }.exceptionOrNull()

        assertTrue(thrown is IOException)
        assertEquals(emptyList<File>(), onDisk())
    }

    @Test
    fun openingTheAppClearsWhatWasLeft() {
        files.replace("jpg") { it.write(1) }
        File(dir, "stray.tmp").writeText("x")

        files.clear()

        assertEquals(emptyList<File>(), onDisk())
    }

    @Test
    fun clearingAFolderThatNeverExistedIsFine() {
        files.clear()
        assertFalse(dir.exists())
    }
}
