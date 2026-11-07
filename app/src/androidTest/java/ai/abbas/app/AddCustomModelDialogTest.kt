package ai.abbas.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end check that the Add Custom Model form explains itself.
 *
 * Driven with UiAutomator rather than a Compose test rule: Espresso's onIdle()
 * reflects into InputManager.getInstance(), which no longer exists on this
 * device's Android release, so every compose-rule test dies before the UI is
 * even measured.
 */
@RunWith(AndroidJUnit4::class)
class AddCustomModelDialogTest {

    private val device: UiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test
    fun guideAndPerFieldHelpAreVisible() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // startActivity() from a background instrumentation process is refused by
        // Android's background-activity-launch rules, so launch via the shell
        // (shell UID is exempt) and wait for the app window to actually appear.
        device.executeShellCommand("am start -n ${context.packageName}/.MainActivity")
        assertTrue(
            "App window never appeared",
            device.wait(Until.hasObject(By.pkg(context.packageName)), 20_000)
        )

        // Chat top bar → drawer → AI Hub → "+" (Add custom model).
        val menu = device.wait(Until.findObject(By.desc("Menu")), 15_000)
        assertNotNull("App did not render its top bar", menu)
        menu.click()
        val hub = device.wait(Until.findObject(By.text("AI Hub")), 10_000)
        assertNotNull("Drawer did not open", hub)
        hub.click()
        val add = device.wait(Until.findObject(By.desc("Add custom model")), 10_000)
        assertNotNull("AI Hub + button missing", add)
        add.click()

        assertNotNull(
            "Guide missing from the dialog",
            device.wait(Until.findObject(By.textContains("Any GGUF model works")), 10_000)
        )

        listOf(
            "How to find Model ID",
            "How to find Display Name",
            "How to find GGUF Filename",
            "How to find Base URL",
            "How to find Estimated RAM (bytes)",
            "How to find Context Size"
        ).forEach { desc ->
            val info = device.wait(Until.findObject(By.desc(desc)), 5_000)
            assertNotNull("Info button missing: $desc", info)
            assertTrue("Info button off screen: $desc", info.visibleBounds.height() > 0)
        }

        // Tapping ⓘ on Base URL opens the "where do I get this" explainer.
        device.findObject(By.desc("How to find Base URL")).click()
        assertNotNull(
            "Base URL explainer did not open",
            device.wait(Until.findObject(By.textContains("The https address of the folder")), 5_000)
        )
        assertNotNull(
            "Explainer is missing its example value",
            device.wait(Until.findObject(By.textContains("Example: https://huggingface.co")), 5_000)
        )
    }
}