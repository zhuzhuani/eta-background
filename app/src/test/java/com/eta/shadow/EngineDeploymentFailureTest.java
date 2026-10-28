package com.eta.shadow;

import org.junit.Test;
import static org.junit.Assert.*;

public class EngineDeploymentFailureTest {
    @Test public void retainsOriginalPortFailureThroughStopAndRollbackMessages() {
        String reason = null;
        for (String line : new String[]{"ETA_ENGINE_ERROR=PORT_BUSY", "ETA_ENGINE_ERROR=STOP_FAILED", "previous payload restored"})
            reason = Engine.deploymentFailure(line, reason);
        assertEquals("PORT_BUSY", reason);
    }
    @Test public void recognizesCredentialAndLockErrorsButNotArbitraryOutput() {
        for (String code : new String[]{"TOKEN_MISSING", "TOKEN_INVALID", "TOKEN_UNREADABLE", "DEPLOY_BUSY"})
            assertEquals(code, Engine.deploymentFailure("ETA_ENGINE_ERROR=" + code, null));
        assertNull(Engine.deploymentFailure("ETA_ENGINE_ERROR=private output", null));
        assertNull(Engine.deploymentFailure("flock: Bad file descriptor", null));
        assertEquals("NO_ROOT", Engine.deploymentFailure("ETA_NO_ROOT", null));
    }
}
