package io.github.mangi.eta.agent.runtime

import org.junit.Assert.*
import org.junit.Test

class AutomationToolPolicyTest {
    @Test fun unattendedRunsCannotManageTasksOrEscapeThroughShellAndChildren() {
        listOf("tasks_create", "tasks_run", "tasks_update", "terminal", "terminal_job", "terminal_daemon", "subagent", "subagent_manage",
            "write_file", "edit_file", "memory_write", "skills_install_from_github").forEach { assertNotNull(it, AutomationToolPolicy.rejection(it)) }
    }
    @Test fun mainScreenGuiIsDeniedIncludingNontrivialToolNames() {
        listOf("launch_app", "open_uri", "tap_area", "tap_element", "long_press_element", "replace_text", "paste_text",
            "open_system_panel", "browser_use", "set_alarm", "set_timer").forEach { assertNotNull(it, AutomationToolPolicy.rejection(it)) }
    }
    @Test fun hiddenDisplayAndReadOnlyToolsRetainTheirOwnExecutionGuards() {
        listOf("screen_open", "screen_shot", "screen_dump", "screen_tap", "screen_finish", "get_current_context", "fetch_url").forEach {
            assertNull(it, AutomationToolPolicy.rejection(it))
        }
    }
}
