package top.rootu.dddplayer.logic

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class UpdateManagerTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val updateManager = UpdateManager(context)

    @Test
    fun `isNewer named tag same or derived build`() {
        assertFalse(updateManager.isNewer("jojo-adhd-edition", "jojo-adhd-edition"))
        assertFalse(updateManager.isNewer("jojo-adhd-edition", "jojo-adhd-edition-3-gabc1234"))
        assertFalse(updateManager.isNewer("jojo-adhd-edition", "jojo-adhd-edition-dirty"))
        assertFalse(updateManager.isNewer("jojo-adhd-edition", "jojo-adhd-edition-3-gabc1234-dirty"))
    }

    @Test
    fun `isNewer cross tag comparisons`() {
        assertTrue(updateManager.isNewer("v1.9.0", "jojo-adhd-edition"))
        assertTrue(updateManager.isNewer("jojo-v2", "jojo-adhd-edition"))
        assertTrue(updateManager.isNewer("jojo", "jojo-adhd-edition"))
        assertTrue(updateManager.isNewer("jojo-adhd-edition", "1.8.0"))
        assertTrue(updateManager.isNewer("v1.9.0", "1.8.0-4-gabc1234"))
        assertTrue(updateManager.isNewer("v1.9.0", "2025-edition"))
        assertTrue(updateManager.isNewer("v1.8.0", "1.8.0-jojo"))
        assertTrue(updateManager.isNewer("v1.9.0", "jojo-adhd-edition-dirty"))
    }

    @Test
    fun `isNewer same or older numeric`() {
        assertFalse(updateManager.isNewer("v1.8.0", "1.8.0"))
        assertFalse(updateManager.isNewer("v1.8.0", "1.8.0-4-gabc1234"))
        assertFalse(updateManager.isNewer("v1.7.0", "1.8.0"))
    }

    @Test
    fun `isOwnRelease filters foreign repos`() {
        assertTrue(
            updateManager.isOwnRelease(
                UpdateInfo("v1", "", "https://github.com/EdemBey/dddplayer/releases/download/v1/app-release.apk", 1)
            )
        )
        assertFalse(
            updateManager.isOwnRelease(
                UpdateInfo("v1", "", "https://github.com/usmanec/dddplayer/releases/download/v1/app-release.apk", 1)
            )
        )
    }

    @Test
    fun `release without an apk asset offers nothing to install`() {
        val manager = UpdateManager(org.robolectric.RuntimeEnvironment.getApplication())
        val zipOnly = org.json.JSONArray("""[{"name":"sources.zip"}]""")
        val withApk = org.json.JSONArray("""[{"name":"notes.txt"},{"name":"app-release.APK"}]""")
        assertEquals(null, manager.apkAsset(zipOnly))
        assertEquals("app-release.APK", manager.apkAsset(withApk)?.getString("name"))
    }
}
