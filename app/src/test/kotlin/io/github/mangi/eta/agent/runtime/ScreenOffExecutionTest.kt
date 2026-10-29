package io.github.mangi.eta.agent.runtime

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Intent
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.datastore.SettingsDataStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ScreenOffExecutionTest {
    @Test
    fun screenOffPausesByDefault() = withRun(enabled = false) { service, run ->
        screenOff(service)
        assertTrue(run.session.controller.isPaused)
        assertTrue(run.pausedForScreenOff)
    }

    @Test
    fun experimentKeepsActiveRunRunning() = withRun(enabled = true) { service, run ->
        screenOff(service)
        assertFalse(run.session.controller.isPaused)
        assertFalse(run.pausedForScreenOff)
    }

    @Test
    fun experimentDoesNotResumeManualPause() = withRun(enabled = true) { service, run ->
        run.session.controller.pause()
        screenOff(service)
        assertTrue(run.session.controller.isPaused)
        assertFalse(run.pausedForScreenOff)
    }

    @Test
    fun disablingExperimentRestoresPauseOnNextScreenOff() = withRun(enabled = true) { service, run ->
        screenOff(service)
        assertFalse(run.session.controller.isPaused)
        requireNotNull(Prefs.localAgentPreferences()).edit()
            .putBoolean(Prefs.Keys.AGENT_SCREEN_OFF_EXECUTION, false).commit()
        screenOff(service)
        assertTrue(run.session.controller.isPaused)
        assertTrue(run.pausedForScreenOff)
    }

    @Test
    fun screenOnDoesNotResumeAnAutomaticallyPausedRun() = withRun(enabled = false) { service, run ->
        screenOff(service)
        val display = service.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(0)
        org.robolectric.Shadows.shadowOf(display).setState(android.view.Display.STATE_ON)
        val receiver = AgentRuntimeService::class.java.getDeclaredField("screenReceiver").apply {
            isAccessible = true
        }.get(service) as BroadcastReceiver
        receiver.onReceive(service, Intent(Intent.ACTION_SCREEN_ON))
        assertTrue(run.session.controller.isPaused)
    }

    private fun screenOff(service: AgentRuntimeService) {
        val display = service.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(0)
        org.robolectric.Shadows.shadowOf(display).setState(android.view.Display.STATE_OFF)
        val receiver = AgentRuntimeService::class.java.getDeclaredField("screenReceiver").apply {
            isAccessible = true
        }.get(service) as BroadcastReceiver
        receiver.onReceive(service, Intent(Intent.ACTION_SCREEN_OFF))
    }

    private fun withRun(enabled: Boolean, block: (AgentRuntimeService, RuntimeRunRegistry.Run) -> Unit) {
        val app = RuntimeEnvironment.getApplication()
        SettingsDataStore.init(app)
        Prefs.initLocal(app)
        val prefs = requireNotNull(Prefs.localAgentPreferences())
        val key = Prefs.Keys.AGENT_SCREEN_OFF_EXECUTION
        val previous = prefs.all[key] as? Boolean
        prefs.edit().putBoolean(key, enabled).commit()
        val controller = Robolectric.buildService(AgentRuntimeService::class.java).create()
        try {
            val service = controller.get()
            val request = AgentRuntimeWire.RunRequest(
                runId = "screen-off-test",
                prompt = "test",
                config = AgentModelClient.ModelConfig(
                    baseUrl = "https://example.invalid/v1", apiKey = "test", model = "test", systemPrompt = "",
                ),
                images = emptyList(),
            )
            val registry = AgentRuntimeService::class.java.getDeclaredField("runs").apply {
                isAccessible = true
            }.get(service) as RuntimeRunRegistry
            val run = RuntimeRunRegistry.Run(request, AgentRuntimeSession(request.runId)).apply {
                prepared(request)
                queued = false
            }
            registry.admit(request) { run }
            block(service, run)
        } finally {
            val display = controller.get().getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(0)
            org.robolectric.Shadows.shadowOf(display).setState(android.view.Display.STATE_ON)
            controller.destroy()
            prefs.edit().apply {
                if (previous == null) remove(key) else putBoolean(key, previous)
            }.commit()
        }
    }
}
