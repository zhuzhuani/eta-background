package io.github.mangi.eta.agent.tasks

import java.net.URI

/** An explicit outcome, not an inference from a process exit code or its stdout. */
internal enum class TaskRunStatus { Succeeded, Partial, Failed, Cancelled, Unconfirmed }

internal data class TaskRunResult(
    val runId: String,
    val taskId: String? = null,
    val conversationId: String? = null,
    val title: String,
    val status: TaskRunStatus,
    val summary: String,
    val report: String,
    val startedAt: Long,
    val completedAt: Long,
    val attachments: List<TaskResultAttachment> = emptyList(),
    val diagnosticJobId: String? = null,
    val source: String = "",
)

/** Exactly one of [privateFile] (a store-owned opaque filename) and [httpsUrl] is set.
 * Never interpret these fields as a content URI or grant external storage access.
 */
internal data class TaskResultAttachment(
    val name: String,
    val mimeType: String = "application/octet-stream",
    val privateFile: String? = null,
    val httpsUrl: String? = null,
    val sizeBytes: Long? = null,
) {
    companion object {
        fun https(url: String, name: String, mimeType: String = "application/octet-stream") =
            TaskResultAttachment(name = name, mimeType = mimeType, httpsUrl = url).also { it.validate() }
    }

    internal fun validate() {
        require(name.isNotBlank() && name.length <= 256 && name.none { it.isISOControl() })
        require(mimeType.length in 1..128 && mimeType.matches(Regex("[a-zA-Z0-9!#$&^_.+-]+/[a-zA-Z0-9!#$&^_.+-]+")))
        require((privateFile == null) != (httpsUrl == null))
        if (privateFile != null) {
            require(privateFile.matches(Regex("[0-9a-f]{64}\\.bin")))
            require(sizeBytes != null && sizeBytes in 0..TaskRunResultStore.MAX_ATTACHMENT_BYTES)
        } else {
            require(sizeBytes == null)
            require(httpsUrl!!.length <= 4096)
            val uri = URI(httpsUrl)
            require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank())
            require(uri.rawUserInfo == null && (uri.port == -1 || uri.port in 1..65535))
            require(httpsUrl.none { it.isWhitespace() || it.isISOControl() })
        }
    }
}
