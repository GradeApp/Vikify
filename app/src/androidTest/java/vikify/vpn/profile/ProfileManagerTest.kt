package vikify.vpn.profile

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

private const val TEST_UUID = "9d975ab1-0479-41fc-975d-d3c52eb73313"

@RunWith(AndroidJUnit4::class)
class ProfileManagerTest {

    @Test
    fun generateConfigJson_mainReality_isValidJson() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = ProfileManager(context)
        val json = manager.generateConfigJson(ProfileType.MAIN_REALITY, TEST_UUID)
        assertTrue("Generated config must be valid JSON", runCatching { JSONObject(json); true }.getOrDefault(false))
        assertTrue("Config must contain TEST UUID", json.contains(TEST_UUID))
        assertTrue("Config must not contain placeholder", !json.contains("\${UUID}"))
    }

    @Test
    fun generateConfigJson_allProfiles_isValidJson() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = ProfileManager(context)
        for (profile in ProfileType.entries) {
            val json = manager.generateConfigJson(profile, TEST_UUID)
            assertTrue("$profile must produce valid JSON", runCatching { JSONObject(json); true }.getOrDefault(false))
        }
    }
}
