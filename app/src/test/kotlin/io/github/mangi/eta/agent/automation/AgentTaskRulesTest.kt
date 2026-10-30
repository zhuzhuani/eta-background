package io.github.mangi.eta.agent.automation

import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentTaskRulesTest {
    @Test fun onceIsConsumedAndIntervalsSkipMissedOccurrences() {
        val once = AgentTaskRules.validate(JSONObject("""{"type":"once","at":"2026-10-10T09:00:00+08:00"}"""))
        assertEquals(Instant.parse("2026-10-10T01:00:00Z").toEpochMilli(), AgentTaskRules.nextTime(once, 0, true))
        assertNull(AgentTaskRules.nextTime(once, 0, false))
        val interval = AgentTaskRules.validate(JSONObject("""{"type":"interval","seconds":3600,"startAt":"2026-10-10T09:00:00+08:00"}"""))
        assertEquals(Instant.parse("2026-10-10T04:00:00Z").toEpochMilli(),
            AgentTaskRules.nextTime(interval, Instant.parse("2026-10-10T03:20:00Z").toEpochMilli(), false))
    }
    @Test fun dailySkipsDstGapAndUsesEarlierRepeatedTimeOnly() {
        val gap = AgentTaskRules.validate(JSONObject("""{"type":"daily","time":"02:30","timeZone":"America/New_York"}"""))
        assertEquals(Instant.parse("2026-03-09T06:30:00Z").toEpochMilli(),
            AgentTaskRules.nextTime(gap, Instant.parse("2026-03-08T00:00:00Z").toEpochMilli(), false))
        val overlap = AgentTaskRules.validate(JSONObject("""{"type":"daily","time":"01:30","timeZone":"America/New_York"}"""))
        assertEquals(Instant.parse("2026-11-02T06:30:00Z").toEpochMilli(),
            AgentTaskRules.nextTime(overlap, Instant.parse("2026-11-01T05:45:00Z").toEpochMilli(), false))
    }
    @Test fun weekdaysAndSystemEventConditionsAreDeterministic() {
        val daily = AgentTaskRules.validate(JSONObject().put("type", "daily").put("time", "09:00").put("timeZone", "Asia/Shanghai")
            .put("weekdays", JSONArray(listOf(1,2,3,4,5))))
        assertEquals(Instant.parse("2026-10-12T01:00:00Z").toEpochMilli(),
            AgentTaskRules.nextTime(daily, Instant.parse("2026-10-09T02:00:00Z").toEpochMilli(), false))
        val event = AgentTaskRules.validate(JSONObject("""{"type":"network_connected","filters":{"transport":"wifi"},"conditions":{"charging":true}}"""))
        assertTrue(AgentTaskRules.matches(event,"network_connected",JSONObject("""{"transport":"wifi"}""")))
        assertFalse(AgentTaskRules.matches(event,"network_connected",JSONObject("""{"transport":"cellular"}""")))
        assertTrue(AgentTaskRules.conditionsMatch(event,mapOf("charging" to true)))
        assertFalse(AgentTaskRules.conditionsMatch(event,emptyMap()))
    }
    @Test fun unsupportedSourcesAndUnsafeRulesAreRejected() {
        listOf("""{"type":"notification_posted"}""", """{"type":"task_completed"}""",
            """{"type":"interval","seconds":1}""", """{"type":"interval","seconds":900.5}""",
            """{"type":"daily","time":"25:00","timeZone":"UTC"}""",
            """{"type":"daily","time":"09:00","timeZone":"UTC","weekdays":[1,1]}""",
            """{"type":"screen_off","conditions":{"unlocked":"true"}}""").forEach {
            assertThrows(Exception::class.java) { AgentTaskRules.validate(JSONObject(it)) }
        }
    }
}
