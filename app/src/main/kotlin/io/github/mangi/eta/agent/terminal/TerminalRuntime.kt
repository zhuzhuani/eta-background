package io.github.mangi.eta.agent.terminal

import android.content.Context
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.runtime.AgentExecutionService
import io.github.mangi.eta.agent.tasks.TaskCommandResultReader
import io.github.mangi.eta.agent.tasks.TaskRunResultStore
import io.github.mangi.eta.agent.tasks.TaskConversationDeliveryStore
import java.io.File

internal enum class LinuxExecutionBackend(val wireName: String) {
    CHROOT("chroot"),
    PROOT("proot"),
}

/** App 进程的终端运行条件；读取能力不会触发 su 授权。 */
internal object TerminalRuntime {
    const val ROOT_WORKSPACE_PATH = "/data/local/tmp/eta"
    const val LINUX_WORKSPACE_PATH = "/workspace"

    @Volatile private var appContext: Context? = null
    private var backgroundJobs: BackgroundTerminalJobs? = null

    @Synchronized
    fun backgroundJobs(context: Context): BackgroundTerminalJobs {
        val app = context.applicationContext
        backgroundJobs?.let { return it }
        val supervisor = DetachedTaskSupervisor(AndroidAgentLogger, DetachedTaskSupervisor.defaultRecordsFile(app))
        val results = TaskRunResultStore.forContext(app)
        return BackgroundTerminalJobs(
            supervisor = supervisor,
            logger = AndroidAgentLogger,
            acquireLease = { onStop -> AgentExecutionService.acquire(app, "terminal-background-commands", onStop = onStop) },
            releaseLease = { AgentExecutionService.release("terminal-background-commands") },
            onCompleted = { task ->
                // At-least-once completion delivery: reuse a finalized result after any notification retry.
                val result = results.read(task.id) ?: results.save(TaskCommandResultReader.read(
                    task, supervisor.readStructuredResult(task), task.taskId, task.conversationId, results))
                // Commit the conversation outbox before notification ACK; failure remains retryable.
                TaskConversationDeliveryStore.forContext(app).enqueue(result)
                BackgroundCommandNotification.show(app, result)
            },
            completionNotificationsEnabled = { app.getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled() },
        ).also { backgroundJobs = it }
    }

    fun acquireUserTask(id: String, onStop: () -> Unit): Boolean =
        appContext?.let { AgentExecutionService.acquire(it, id, onStop = onStop) } ?: true

    fun releaseUserTask(id: String) {
        if (appContext != null) AgentExecutionService.release(id)
    }

    fun initialize(context: Context) {
        appContext = context.applicationContext
        File(userWorkspacePath).mkdirs()
    }

    val rootAvailable: Boolean get() = RootAccess.isGranted
    val publicStorageGranted: Boolean get() = appContext != null && android.os.Environment.isExternalStorageManager()
    val nativeLibraryDir: File? get() = appContext?.applicationInfo?.nativeLibraryDir?.let(::File)
    val userWorkspacePath: String get() = appContext?.let {
        TerminalPrivateStorage.workspace(it.filesDir).absolutePath
    } ?: File(System.getProperty("java.io.tmpdir"), "eta-terminal-workspace").absolutePath
    val temporaryDirectory: File get() = appContext?.let { File(it.cacheDir, "terminal/proot") }
        ?: File(System.getProperty("java.io.tmpdir"), "eta-proot")

    fun defaultIdentity(environment: TerminalEnvironment, rootfsPath: String? = null): String = when {
        environment.isLinux -> if (LinuxEnvironmentPaths.backendOf(rootfsPath) == LinuxExecutionBackend.PROOT) "user" else "root"
        rootAvailable -> "root"
        else -> "user"
    }

    fun workspace(identity: String): String =
        if (identity == "root") ROOT_WORKSPACE_PATH else userWorkspacePath

    fun nativeExecutable(name: String): File? = nativeLibraryDir?.let { File(it, name) }
        ?.takeIf { it.isFile && it.canExecute() }
}
