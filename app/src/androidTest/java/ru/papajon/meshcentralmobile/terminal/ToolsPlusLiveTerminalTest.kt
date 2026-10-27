package ru.papajon.meshcentralmobile.terminal

import android.app.Application
import android.net.ConnectivityManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.settings.SettingsRepository
import ru.papajon.meshcentralmobile.ui.screens.TerminalScreen
import ru.papajon.meshcentralmobile.ui.theme.MeshCentralMobileTheme
import ru.papajon.meshcentralmobile.workspace.ConnectionWorkspace
import ru.papajon.meshcentralmobile.ui.DeviceTab
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Dedicated emulator only: opt-in toggles its network, never the host or the bench server. */
class ToolsPlusLiveTerminalTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var renderer: TerminalWebRenderer

    @Test fun networkDropAllowsManualReconnectWithHistoryAndNoCommandReplay() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("toolsPlusLive") == "true")
        val root = requireNotNull(InstrumentationRegistry.getArguments().getString("toolsPlusDirectory"))
        val deviceName = requireNotNull(InstrumentationRegistry.getArguments().getString("toolsPlusDevice"))
        require(root.matches(Regex("/opt/meshcentral-livebench/fixture/tools-plus-[a-zA-Z0-9-]+")))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val profile = SettingsRepository(context).activeProfile
        require(profile.serverUrl.trimEnd('/') == "https://10.0.2.2:4443" && profile.pinnedSpkiSha256 != null)
        val workspace = ConnectionWorkspace.get(context.applicationContext as Application)
        lateinit var controller: TerminalController
        try {
            compose.runOnIdle {
                workspace.closeAll()
                workspace.client.configureServer(profile.serverUrl)
                workspace.client.setPinnedServerKey(profile.pinnedSpkiSha256)
                check(workspace.client.loginWithStoredCredentials(profile.serverUrl))
            }
            compose.waitUntil(45_000) { workspace.client.clientState.value.let { it.devicesCurrent && it.socketConnected && !it.requiresSignIn } }
            compose.runOnIdle {
                val device = workspace.client.clientState.value.devices.single { it.name == deviceName && it.conn and 1 != 0 }
                workspace.openDevice(device)
                check(workspace.selectTab(DeviceTab.Terminal))
                controller = requireNotNull(workspace.selectedSession?.terminal)
                renderer = requireNotNull(controller.renderer(context))
            }
            compose.setContent { MeshCentralMobileTheme { TerminalScreen(controller, {}, Modifier.safeDrawingPadding()) } }
            compose.waitUntil(30_000) { controller.state.value.ready }
            compose.waitUntil(15_000) { evaluate("typeof window.meshTerminal") == "object" }
            compose.runOnIdle {
                assertTrue(controller.sendInput("printf x >> $root/terminal-counter; seq 1 120; printf 'TOOLS_''BEFORE\\n'\r"))
            }
            awaitOutput("TOOLS_BEFORE")
            evaluate("window.meshTerminal.scrollToLine(3);true")
            val line = evaluate("window.meshTerminal.viewport().y").toInt()
            assertTrue(line > 0)
            network("disable")
            compose.waitUntil(30_000) { context.getSystemService(ConnectivityManager::class.java).activeNetwork == null }
            compose.waitUntil(30_000) { controller.state.value.canReconnect && !controller.state.value.ready }
            assertTrue(output().contains("TOOLS_BEFORE"))
            screenshot("live-terminal-disconnected")
            network("enable")
            compose.waitUntil(60_000) { workspace.client.clientState.value.let { it.devicesCurrent && it.socketConnected && !it.requiresSignIn } }
            compose.onNodeWithText(context.getString(R.string.terminal_reconnect)).performClick()
            compose.waitUntil(30_000) { controller.state.value.ready }
            compose.waitUntil(15_000) { evaluate("window.meshTerminal.sessionSerial") == "1" }
            assertSame(renderer, controller.renderer(context))
            screenshot("live-terminal-before-viewport-check")
            assertTrue("History must survive the new shell", output().contains("TOOLS_BEFORE"))
            assertEquals(line, evaluate("window.meshTerminal.viewport().y").toInt())
            assertTrue(output().contains("TOOLS_BEFORE"))
            assertTrue(output().contains(context.getString(R.string.terminal_new_session)))
            compose.runOnIdle {
                assertTrue(controller.sendInput("printf 'TOOLS_''AFTER\\n'; printf 'COUNT='; wc -c < $root/terminal-counter\r"))
            }
            awaitOutput("TOOLS_AFTER")
            awaitOutput("COUNT=1")
            screenshot("live-terminal-reconnected")
            File(context.getExternalFilesDir(null), "tools-plus/live-terminal.json").apply {
                parentFile!!.mkdirs()
                writeText("""{"networkDrop":true,"manualReconnect":true,"sameRenderer":true,"viewportPreserved":true,"oldCommandExecutions":1}""")
            }
        } finally {
            network("enable")
            compose.runOnIdle { workspace.closeAll(); workspace.client.close() }
        }
    }

    private fun network(action: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        for (service in listOf("wifi", "data")) automation.executeShellCommand("svc $service $action").use {
            java.io.FileInputStream(it.fileDescriptor).use { input -> input.readBytes() }
        }
    }
    private fun output() = evaluate("window.meshTerminal.selectAll();window.meshTerminal.selection()")
    private fun awaitOutput(value: String) { compose.waitUntil(20_000) { output().contains(value) } }
    private fun evaluate(script: String): String {
        val done = CountDownLatch(1)
        var result = ""
        compose.runOnUiThread { renderer.view.evaluateJavascript(script) {
            result = runCatching { JSONTokener(it).nextValue().toString() }.getOrDefault(""); done.countDown()
        } }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        return result
    }
    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(instrumentation.targetContext.getExternalFilesDir(null), "tools-plus/$name.png").apply { parentFile!!.mkdirs() }
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
