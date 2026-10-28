package io.github.mangi.eta.agent.terminal

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AppStateCommandGuardTest {
    @Test fun blocksDirectCommandsAcrossTerminalVariants() {
        for (command in listOf(
            "pm disable-user --user 0 com.autonavi.minimap",
            "su -c 'cmd package enable com.autonavi.minimap'",
            "/system/bin/pm suspend com.example.app",
            "'pm' 'disable-user' com.example.app",
        )) assertTrue(command, AppStateCommandGuard.rejects(JSONObject().put("command", command)))
    }
    @Test fun permitsReadOnlyInspectionAndUnrelatedDeviceCommands() {
        for (command in listOf("dumpsys package com.autonavi.minimap", "pm list packages -d", "cmd wifi status", "settings get global airplane_mode_on"))
            assertFalse(command, AppStateCommandGuard.rejects(JSONObject().put("command", command)))
    }
}
