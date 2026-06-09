package com.keymusicman.sight.worker

import com.android.ide.common.resources.configuration.DensityQualifier
import com.android.ide.common.resources.configuration.FolderConfiguration
import com.android.resources.Density
import com.android.resources.ResourceType
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class UserResourceRepositoryTest {
    private fun writeResDir(): File {
        val root = createTempDirectory().toFile()
        val res = File(root, "res")
        File(res, "values").mkdirs()
        File(res, "values/strings.xml").writeText(
            """<resources><string name="title">Hi</string></resources>""",
        )
        File(res, "drawable").mkdirs()
        File(res, "drawable/ic_bm.xml").writeText(
            """<vector xmlns:android="http://schemas.android.com/apk/res/android" """ +
                """android:width="24dp" android:height="24dp" android:viewportWidth="24" """ +
                """android:viewportHeight="24"/>""",
        )
        File(res, "drawable-xxxhdpi").mkdirs()
        File(res, "drawable-xxxhdpi/ic_bm.webp").writeBytes(byteArrayOf(1, 2, 3))
        return res
    }

    @Test
    fun `resolves string and drawable from a res dir`() {
        val repo = UserResourceRepository(listOf(writeResDir().absolutePath))
        val byType = repo.configuredFor(FolderConfiguration.createDefault())
        assertNotNull(byType[ResourceType.STRING]?.get("title"))
        assertNotNull(byType[ResourceType.DRAWABLE]?.get("ic_bm"))
    }

    @Test
    fun `selects density-qualified variant for matching config`() {
        val repo = UserResourceRepository(listOf(writeResDir().absolutePath))
        val fc = FolderConfiguration.createDefault().apply {
            densityQualifier = DensityQualifier(Density.create(640)) // xxxhdpi
        }
        val value = repo.configuredFor(fc)[ResourceType.DRAWABLE]?.get("ic_bm")
        assertNotNull(value)
        assertTrue(value.value!!.contains("xxxhdpi"), "expected xxxhdpi variant, got ${value.value}")
    }

    @Test
    fun `missing dirs are skipped, repo reports empty`() {
        val repo = UserResourceRepository(listOf("/does/not/exist"))
        assertTrue(repo.isEmpty())
        assertEquals(emptyMap(), repo.configuredFor(FolderConfiguration.createDefault()))
    }
}
