package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.automation.AgentTaskRules
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class AgentTaskToolCatalogTest {
    private fun definitions() = JSONArray().also(AgentTaskToolCatalog::appendTo).let { tools ->
        (0 until tools.length()).map { tools.getJSONObject(it).getJSONObject("function") }
            .associateBy { it.getString("name") }
    }

    @Test fun explicitNamesMatchStrictSchemasAndReadOnlySubset() {
        val definitions = definitions()
        assertEquals(AgentTaskToolCatalog.names, definitions.keys)
        assertEquals(setOf("tasks_triggers", "tasks_list", "tasks_history"), AgentTaskToolCatalog.readOnlyNames)
        assertTrue(AgentTaskToolCatalog.names.containsAll(AgentTaskToolCatalog.readOnlyNames))
        definitions.values.forEach {
            assertFalse(it.getJSONObject("parameters").getBoolean("additionalProperties"))
        }
    }

    @Test fun creationDescribesUserAuthorizationBestEffortAndNoUnsafeReplay() {
        val create = definitions().getValue("tasks_create")
        val description = create.getString("description")
        assertTrue(description.contains("user explicitly asks"))
        assertTrue(description.contains("automatic/scheduled run"))
        assertTrue(description.contains("best effort"))
        assertTrue(description.contains("uncertain side effects"))
        assertTrue(description.contains("No main-screen GUI"))
        val parameters = create.getJSONObject("parameters")
        val required = parameters.getJSONArray("required")
        assertEquals(setOf("name", "prompt", "trigger"), (0 until required.length()).map(required::getString).toSet())
        val trigger = parameters.getJSONObject("properties").getJSONObject("trigger")
        val types = trigger.getJSONObject("properties").getJSONObject("type").getJSONArray("enum")
        assertEquals(AgentTaskRules.timeTypes + AgentTaskRules.eventTypes, (0 until types.length()).map(types::getString).toSet())
        assertEquals(900, trigger.getJSONObject("properties").getJSONObject("seconds").getInt("minimum"))
    }

    @Test fun updatesRequireRevisionAndAllMutationsForbidAutomaticRuns() {
        val definitions = definitions()
        val required = definitions.getValue("tasks_update").getJSONObject("parameters").getJSONArray("required")
        assertEquals(setOf("taskId", "expectedUpdatedAt"), (0 until required.length()).map(required::getString).toSet())
        (AgentTaskToolCatalog.names - AgentTaskToolCatalog.readOnlyNames).forEach { name ->
            assertTrue(name, definitions.getValue(name).getString("description").contains("automatic/scheduled run"))
        }
    }
}
