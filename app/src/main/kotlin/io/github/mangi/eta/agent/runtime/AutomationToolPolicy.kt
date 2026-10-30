package io.github.mangi.eta.agent.runtime

/** Unattended execution must not acquire the user's real screen or schedule recursive work. */
internal object AutomationToolPolicy {
    private val denied = setOf(
        "terminal", "terminal_job", "terminal_daemon", "run_command", "subagent", "subagent_manage",
        "launch_app", "open_uri", "open_app", "observe_screen", "click", "tap", "long_press",
        "swipe", "scroll", "input_text", "type_text", "press_key", "back", "home", "recents",
        "write_file", "edit_file", "skills_install_from_github", "memory_write", "character_memory_write",
    )
    fun rejection(name: String): String? {
        if (name.startsWith("screen_")) return null // Existing leased hidden-display tools; never implicit main display.
        if (name.startsWith("tasks_") || name.startsWith("subagent") || name.startsWith("browser_") || name in denied ||
            io.github.mangi.eta.agent.tool.AgentToolRequirements.requiresGuiControl(name) ||
            io.github.mangi.eta.agent.tool.AgentToolRequirements.hasForegroundIntentFallback(name)) {
            return """{"ok":false,"code":"AUTOMATION_TOOL_RESTRICTED","message":"自动任务不允许管理其他任务、执行任意Shell或接管主屏；请使用后台虚拟屏工具或在前台手动确认。此工具未执行。"}"""
        }
        return null
    }
}
