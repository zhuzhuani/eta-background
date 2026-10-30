package io.github.mangi.eta.agent.automation

import java.util.concurrent.atomic.AtomicBoolean

/** One JobScheduler invocation: system stop and normal completion have mutually exclusive ownership. */
internal class AgentTaskJobLease {
    val stopped = AtomicBoolean()
    private val finished = AtomicBoolean()
    fun stop() { stopped.set(true); finished.compareAndSet(false, true) }
    fun finishNormally(): Boolean = !stopped.get() && finished.compareAndSet(false, true)
}
