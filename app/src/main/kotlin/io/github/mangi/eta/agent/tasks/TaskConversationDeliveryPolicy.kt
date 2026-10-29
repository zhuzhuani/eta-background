package io.github.mangi.eta.agent.tasks

/** Shared recovery policy; no UI, model invocation or implicit conversation creation. */
internal object TaskConversationDeliveryPolicy {
    enum class Decision { Defer, Append, AcknowledgeDeleted }

    fun decide(
        delivery: TaskConversationDeliveryStore.Delivery,
        result: TaskRunResult?,
        exists: Boolean,
        busy: Boolean,
    ): Decision = when {
        !exists -> Decision.AcknowledgeDeleted
        busy -> Decision.Defer
        result == null || result.runId != delivery.runId || result.conversationId != delivery.conversationId -> Decision.Defer
        else -> Decision.Append
    }

    /** Invoked only after append/deletion state is ready; failed persistence leaves all refs pending. */
    suspend fun commit(
        ready: List<TaskConversationDeliveryStore.Delivery>,
        persist: suspend () -> Boolean,
        acknowledge: (TaskConversationDeliveryStore.Delivery) -> Unit,
    ) {
        if (ready.isNotEmpty() && persist()) ready.forEach(acknowledge)
    }
}
