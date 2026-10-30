package io.github.mangi.eta.agent.automation

import org.junit.Assert.*
import org.junit.Test

class AgentTaskJobLeaseTest {
    @Test fun normalCompletionOwnsJobFinishedExactlyOnce() {
        val lease = AgentTaskJobLease()
        assertTrue(lease.finishNormally())
        assertFalse(lease.finishNormally())
    }
    @Test fun osStopSuppressesLaterJobFinished() {
        val lease = AgentTaskJobLease()
        lease.stop()
        assertTrue(lease.stopped.get())
        assertFalse(lease.finishNormally())
    }
    @Test fun stoppingOldInvocationCannotAffectNextInvocation() {
        val first = AgentTaskJobLease()
        val second = AgentTaskJobLease()
        first.stop()
        assertFalse(first.finishNormally())
        assertTrue(second.finishNormally())
    }
}
