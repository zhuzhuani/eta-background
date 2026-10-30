package io.github.mangi.eta.ui.screens.tasks

import org.junit.Assert.*
import org.junit.Test

class AgentTriggerCatalogTest {
    @Test fun triggerGroupsSeparateDeviceConnectionsAppsAndEtaEvents() {
        assertEquals(TaskTriggerGroup.TIME, TaskTriggerUi("daily", true).group)
        assertEquals(TaskTriggerGroup.CONNECTION, TaskTriggerUi("bluetooth_connected", false).group)
        assertEquals(TaskTriggerGroup.APPS, TaskTriggerUi("notification_posted", true).group)
        assertEquals(TaskTriggerGroup.ETA, TaskTriggerUi("task_completed", true).group)
        assertEquals(TaskTriggerGroup.DEVICE, TaskTriggerUi("device_unlocked", true).group)
    }

    @Test fun onlyTerminalExecutionsCanOpenTheirExactReport() {
        listOf("completed", "failed", "cancelled", "interrupted").forEach {
            assertTrue(it, canOpenTaskRunReport(it))
        }
        listOf("ready", "queued", "running", "configuration_required", "unknown").forEach {
            assertFalse(it, canOpenTaskRunReport(it))
        }
    }
}
