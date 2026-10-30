package io.github.mangi.eta.agent.runtime

/** Trusted same-process scheduler claim, never a model-supplied handoff alone. */
internal object AutomationRunRegistry {
    fun contains(runId: String): Boolean = io.github.mangi.eta.agent.automation.AgentTaskScheduler.isRegistered(runId)
}
