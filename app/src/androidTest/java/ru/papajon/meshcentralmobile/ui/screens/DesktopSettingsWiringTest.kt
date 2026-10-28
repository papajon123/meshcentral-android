package ru.papajon.meshcentralmobile.ui.screens

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.mesh.*
import ru.papajon.meshcentralmobile.model.ConnectionState
import ru.papajon.meshcentralmobile.security.SecureVault
import ru.papajon.meshcentralmobile.settings.DesktopPreferences
import ru.papajon.meshcentralmobile.ui.screens.session.DesktopPipHarnessActivity
import ru.papajon.meshcentralmobile.ui.screens.session.LocalDesktopPip
import ru.papajon.meshcentralmobile.ui.theme.MeshCentralMobileTheme

/** Actual RemoteDesktopScreen wiring; the transport records packets without any server or credentials. */
class DesktopSettingsWiringTest {
    @get:Rule val compose = createAndroidComposeRule<DesktopPipHarnessActivity>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var client: MeshCentralClient
    private lateinit var state: MutableStateFlow<RemoteDesktopState>
    private val packets = mutableListOf<ByteArray>()

    @Suppress("UNCHECKED_CAST")
    private fun screen(windows: Boolean = true) {
        client = MeshCentralClient(SecureVault(compose.activity, "desktop-wiring-fixture"), scope)
        val relay = client.javaClass.getDeclaredField("relay").apply { isAccessible = true }.get(client)
        state = relay.javaClass.getDeclaredField("_desktopState").apply { isAccessible = true }.get(relay) as MutableStateFlow<RemoteDesktopState>
        val socket = object : WebSocket {
            override fun request() = Request.Builder().url("https://example.invalid/").build()
            override fun queueSize() = 0L
            override fun send(text: String) = true
            override fun send(bytes: ByteString): Boolean { packets += bytes.toByteArray(); return true }
            override fun close(code: Int, reason: String?) = true
            override fun cancel() {}
        }
        relay.javaClass.getDeclaredField("relaySocket").apply { isAccessible = true }.set(relay, socket)
        // A paired relay has already sent the protocol number; until then the relay sends nothing.
        relay.javaClass.getDeclaredField("protocolNumberSent").apply { isAccessible = true }.setBoolean(relay, true)
        state.value = RemoteDesktopState(device = MeshDevice("node//wiring", "mesh//fixture", "Fixture",
            conn = 1, agentId = if (windows) 3 else 6, osDescription = if (windows) "Windows" else "Linux", rights = 8),
            connectionState = ConnectionState.Connected, handshakeComplete = true,
            framebuffer = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888), remoteWidth = 640, remoteHeight = 480)
        compose.setContent {
            CompositionLocalProvider(LocalDesktopPip provides compose.activity.pip) {
                MeshCentralMobileTheme { RemoteDesktopScreen(client, {}, {}, shortcutProfileId = "wiring") }
            }
        }
        compose.waitForIdle()
        compose.waitUntil(5000) { compose.activity.window.decorView.hasWindowFocus() }
    }

    @After fun cleanup() {
        if (::client.isInitialized) client.close()
        DesktopPreferences(compose.activity).savePipAutomatic(false)
        scope.cancel()
    }

    @Test fun screenPassesTargetOsToHardwareKeyboardInsteadOfAlwaysWindows() {
        screen(windows = true)
        fun pressAndCheck(windows: Boolean) {
            compose.runOnIdle { packets.clear() }
            compose.runOnUiThread {
                val callback = compose.activity.window.callback
                callback.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_RIGHT))
                callback.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CTRL_RIGHT))
            }
            compose.runOnIdle {
                val keys = packets.filter { it.size >= 5 && it[1].toInt() == 1 }
                assertEquals(2, keys.size)
                assertArrayEquals(KvmInputEncoder.key(17, true, windows), keys[0])
                assertArrayEquals(KvmInputEncoder.key(17, false, windows), keys[1])
            }
        }
        pressAndCheck(true)
        compose.runOnIdle { state.value = state.value.copy(device = state.value.device!!.copy(agentId = 6, osDescription = "Linux")) }
        pressAndCheck(false)
    }

    @Test fun automaticPipChoiceReachesScreenMenuHostAndPersistence() {
        DesktopPreferences(compose.activity).savePipAutomatic(false)
        screen()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.session_dock_more)).performClick()
        compose.onNodeWithTag("desktop_pip_auto").performScrollTo().assertIsNotSelected().performClick().assertIsSelected()
        compose.runOnIdle { assertTrue(DesktopPreferences(compose.activity).loadPipAutomatic()) }
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        compose.onNodeWithTag("desktop_pip_auto").assertDoesNotExist()
        compose.waitUntil(5000) { compose.activity.window.decorView.hasWindowFocus() }
        compose.runOnUiThread {
            for (name in listOf("eligible", "automatic"))
                assertTrue("UI must enable PiP $name", compose.activity.pip.javaClass.getDeclaredField(name)
                    .apply { isAccessible = true }.getBoolean(compose.activity.pip))
        }
        android.os.SystemClock.sleep(500)
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("input keyevent KEYCODE_HOME")
        ).use { it.readBytes() }
        compose.waitUntil(10000) { compose.activity.isInPictureInPictureMode && compose.activity.pip.active }
        compose.mainClock.advanceTimeBy(1000)
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        android.os.SystemClock.sleep(700)
        compose.runOnUiThread {
            compose.activity.startActivity(android.content.Intent(compose.activity.intent)
                .setFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
        compose.waitUntil(10000) { !compose.activity.isInPictureInPictureMode }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.session_dock_more)).performClick()
        compose.onNodeWithTag("desktop_pip_auto").performScrollTo().assertIsSelected()
    }
}
