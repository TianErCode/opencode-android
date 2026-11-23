package com.tianercode.opencode.runtime.local

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.tianercode.opencode.OpenCodeApplication
import com.tianercode.opencode.MainActivity
import com.tianercode.opencode.R
import com.tianercode.opencode.core.lifecycle.AppForeground
import com.tianercode.opencode.core.runtime.RuntimeWorkTracker
import com.tianercode.opencode.runtime.LocalAgent
import com.tianercode.opencode.runtime.LocalRuntimeStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal enum class LocalRuntimeServiceCommand {
    InstallAndStart,
    Start,
    Reinstall,
    Update,
    Rollback,
    Delete,
    Stop,
    Restart,
    Restore,
    Ignore,
}

internal fun localRuntimeServiceCommand(action: String?): LocalRuntimeServiceCommand =
    when (action) {
        LocalRuntimeService.ACTION_INSTALL_AND_START -> LocalRuntimeServiceCommand.InstallAndStart
        LocalRuntimeService.ACTION_START -> LocalRuntimeServiceCommand.Start
        LocalRuntimeService.ACTION_REINSTALL -> LocalRuntimeServiceCommand.Reinstall
        LocalRuntimeService.ACTION_UPDATE -> LocalRuntimeServiceCommand.Update
        LocalRuntimeService.ACTION_ROLLBACK -> LocalRuntimeServiceCommand.Rollback
        LocalRuntimeService.ACTION_DELETE -> LocalRuntimeServiceCommand.Delete
        LocalRuntimeService.ACTION_STOP -> LocalRuntimeServiceCommand.Stop
        LocalRuntimeService.ACTION_RESTART -> LocalRuntimeServiceCommand.Restart
        null -> LocalRuntimeServiceCommand.Restore
        else -> LocalRuntimeServiceCommand.Ignore
    }













internal fun clearsUserStoppedFlag(command: LocalRuntimeServiceCommand): Boolean =
    when (command) {
        LocalRuntimeServiceCommand.Start,
        LocalRuntimeServiceCommand.InstallAndStart,
        LocalRuntimeServiceCommand.Restart,
        LocalRuntimeServiceCommand.Reinstall,
        LocalRuntimeServiceCommand.Update,
        LocalRuntimeServiceCommand.Rollback,
        -> true
        LocalRuntimeServiceCommand.Stop,
        LocalRuntimeServiceCommand.Delete,
        LocalRuntimeServiceCommand.Restore,
        LocalRuntimeServiceCommand.Ignore,
        -> false
    }









internal fun localRuntimeInstallAgents(ids: Array<String>?): Set<LocalAgent> =
    ids?.mapNotNull { id -> LocalAgent.entries.firstOrNull { it.id == id } }
        ?.toSet()
        ?.takeIf(Set<LocalAgent>::isNotEmpty)
        ?: setOf(LocalAgent.OPEN_CODE)

















internal fun localRuntimeNeedsWakeLock(
    status: LocalRuntimeStatus,
    hasActiveWork: Boolean,
): Boolean =
    when (status) {
        is LocalRuntimeStatus.Installing,
        is LocalRuntimeStatus.Starting,
        is LocalRuntimeStatus.Updating,
        -> true
        is LocalRuntimeStatus.Ready -> hasActiveWork
        LocalRuntimeStatus.NotInstalled,
        is LocalRuntimeStatus.Stopped,
        is LocalRuntimeStatus.Broken,
        is LocalRuntimeStatus.UnsupportedAbi,
        
        
        
        LocalRuntimeStatus.AwaitingStoragePermission,
        -> false
    }

private const val WATCHDOG_ACTIVE_INTERVAL_MILLIS = 5_000L
private const val WATCHDOG_IDLE_INTERVAL_MILLIS = 60_000L


internal const val IDLE_STOP_TIMEOUT_MILLIS = 15 * 60 * 1000L

















internal fun watchdogIntervalMillis(
    status: LocalRuntimeStatus,
    hasActiveWork: Boolean,
): Long =
    when {
        status is LocalRuntimeStatus.Ready && !hasActiveWork -> WATCHDOG_IDLE_INTERVAL_MILLIS
        status is LocalRuntimeStatus.Broken -> WATCHDOG_IDLE_INTERVAL_MILLIS
        status is LocalRuntimeStatus.UnsupportedAbi -> WATCHDOG_IDLE_INTERVAL_MILLIS
        status is LocalRuntimeStatus.NotInstalled -> WATCHDOG_IDLE_INTERVAL_MILLIS
        else -> WATCHDOG_ACTIVE_INTERVAL_MILLIS
    }













internal fun shouldStopIdleRuntime(
    status: LocalRuntimeStatus,
    hasActiveWork: Boolean,
    appInForeground: Boolean,
    idleForMillis: Long,
    timeoutMillis: Long,
): Boolean =
    status is LocalRuntimeStatus.Ready &&
        !hasActiveWork &&
        !appInForeground &&
        idleForMillis >= timeoutMillis

internal class RestartBackoff(
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val baseDelayMs: Long = 1_000L,
    private val maxDelayMs: Long = 60_000L,
    private val resetWindowMs: Long = 30_000L,
) {
    private var consecutiveCrashes = 0
    private var lastCrashAtMillis = 0L

    fun recordCrash(): Long {
        val now = nowMillis()
        if (now - lastCrashAtMillis > resetWindowMs) {
            consecutiveCrashes = 1
        } else {
            consecutiveCrashes++
        }
        lastCrashAtMillis = now
        return delayMillis()
    }

    fun reset() {
        consecutiveCrashes = 0
        lastCrashAtMillis = 0L
    }

    fun recordUptime(uptimeMillis: Long) {
        if (uptimeMillis >= resetWindowMs) {
            reset()
        }
    }

    private fun delayMillis(): Long {
        if (consecutiveCrashes <= 1) return 0L
        val delay = baseDelayMs * (1L shl (consecutiveCrashes - 2).coerceAtMost(6))
        return delay.coerceAtMost(maxDelayMs)
    }
}









internal class IdleStopTracker(private val nowMillis: () -> Long = SystemClock::elapsedRealtime) {
    



    private var idleSinceMillis: Long? = null

    
    fun update(idleNow: Boolean): Long {
        val now = nowMillis()
        idleSinceMillis = if (idleNow) idleSinceMillis ?: now else null
        return idleSinceMillis?.let { now - it } ?: 0L
    }
}










internal suspend fun runIdleStopSequence(
    markInProgress: (Boolean) -> Unit,
    stop: suspend () -> Unit,
    onStopped: () -> Unit,
) {
    markInProgress(true)
    try {
        stop()
    } finally {
        markInProgress(false)
        onStopped()
    }
}

class LocalRuntimeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var operation: Job? = null

    





    private var operationKind: String? = null

    
    @Volatile private var operationToken = 0
    private var watchdogJob: Job? = null
    private var exitMonitorJob: Job? = null

    @Volatile private var autoRestartEnabled = false
    private lateinit var app: OpenCodeApplication
    private lateinit var manager: LocalRuntimeManager
    private lateinit var runtimeWork: RuntimeWorkTracker
    private lateinit var appForeground: AppForeground
    private val backoff = RestartBackoff()
    private var inForeground = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var shuttingDown = false

    



    private val idleTracker = IdleStopTracker()

    override fun onCreate() {
        super.onCreate()
        app = application as OpenCodeApplication
        manager = app.localRuntimeManager
        runtimeWork = app.runtimeWork
        appForeground = app.appForeground
        createChannel()
        
        
        inForeground =
            runCatching { startForeground(NOTIFICATION_ID, notification(manager.status())) }
                .onFailure { error -> Log.w(TAG, "Could not enter the foreground", error) }
                .isSuccess
        if (!inForeground) return
        syncWakeLock(manager.status(), runtimeWork.active.value)
        scope.launch {
            manager.state.collectLatest { status ->
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, notification(status))
                if (status is LocalRuntimeStatus.Ready) {
                    backoff.reset()
                }
            }
        }
        
        
        
        scope.launch {
            combine(manager.state, runtimeWork.active, ::Pair).collectLatest { (status, hasActiveWork) ->
                syncWakeLock(status, hasActiveWork)
            }
        }
        manager.setOnExit { exitCode, pid, uptime ->
            if (!autoRestartEnabled) return@setOnExit
            backoff.recordUptime(uptime)
            val delayMs = backoff.recordCrash()
            exitMonitorJob?.cancel()
            exitMonitorJob =
                scope.launch(Dispatchers.IO) {
                    if (delayMs > 0) delay(delayMs)
                    if (!isActive || !autoRestartEnabled) return@launch
                    manager.ensureRunning()
                }
        }
        startWatchdog()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val command = localRuntimeServiceCommand(intent?.action)
        
        
        
        
        
        
        if (clearsUserStoppedFlag(command)) app.settings.localRuntimeStoppedByUser = false
        if (!inForeground) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (command) {
            LocalRuntimeServiceCommand.InstallAndStart -> {
                autoRestartEnabled = true
                val agents = localRuntimeInstallAgents(intent?.getStringArrayExtra(EXTRA_AGENTS))
                launchOperation("install") { manager.installAndStart(agents) }
            }
            LocalRuntimeServiceCommand.Start -> {
                autoRestartEnabled = true
                launchOperation("start") { manager.start() }
            }
            LocalRuntimeServiceCommand.Reinstall -> {
                autoRestartEnabled = true
                launchOperation("reinstall") { manager.reinstall() }
            }
            LocalRuntimeServiceCommand.Update -> {
                autoRestartEnabled = true
                launchOperation("update") { manager.updateToLatest() }
            }
            LocalRuntimeServiceCommand.Rollback -> {
                autoRestartEnabled = true
                launchOperation("rollback") { manager.rollback() }
            }
            LocalRuntimeServiceCommand.Delete -> {
                autoRestartEnabled = false
                launchOperation("delete") {
                    manager.deleteRuntime()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            LocalRuntimeServiceCommand.Stop -> {
                autoRestartEnabled = false
                
                
                
                app.settings.localRuntimeStoppedByUser = true
                launchOperation("stop") {
                    manager.stop()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            LocalRuntimeServiceCommand.Restart -> {
                autoRestartEnabled = true
                launchOperation("restart") {
                    manager.stop()
                    manager.start()
                }
            }
            LocalRuntimeServiceCommand.Restore -> {
                autoRestartEnabled = true
                if (manager.status() is LocalRuntimeStatus.Stopped) {
                    launchOperation("ensure") { manager.ensureRunning() }
                }
            }
            LocalRuntimeServiceCommand.Ignore -> Unit
        }
        return START_STICKY
    }

    override fun onDestroy() {
        autoRestartEnabled = false
        manager.setOnExit(null)
        operation?.cancel()
        exitMonitorJob?.cancel()
        watchdogJob?.cancel()
        scope.cancel()
        shutDownWakeLock()
        
        
        
        
        app.setIdleStopInProgress(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    











    private fun launchOperation(
        kind: String,
        block: suspend () -> Unit,
    ) {
        val running = operation
        
        
        
        
        
        
        
        
        
        
        
        
        
        
        
        
        if (running?.isActive == true && operationKind == kind) {
            Log.i(TAG, "operation: ignoring a duplicate \"$kind\" request; one is already running")
            return
        }
        running?.cancel()
        
        
        
        operationToken += 1
        val token = operationToken
        operationKind = kind
        operation =
            scope.launch(Dispatchers.IO) {
                try {
                    runtimeWork.withLease(RUNTIME_OPERATION_LEASE_TAG) { block() }
                } finally {
                    if (operationToken == token) operationKind = null
                }
            }
    }

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob =
            scope.launch(Dispatchers.IO) {
                val watchdog = LocalRuntimeWatchdog()
                while (isActive) {
                    delay(watchdogIntervalMillis(manager.status(), runtimeWork.active.value))
                    val status = manager.status()
                    val hasActiveWork = runtimeWork.active.value
                    
                    
                    
                    
                    
                    
                    syncWakeLock(status, hasActiveWork)

                    if (checkIdleStop(status, hasActiveWork)) return@launch

                    if (!autoRestartEnabled) continue
                    if (watchdog.observe(status)) {
                        manager.ensureRunning()
                    }
                }
            }
    }

    









    private fun checkIdleStop(
        status: LocalRuntimeStatus,
        hasActiveWork: Boolean,
    ): Boolean {
        val appInForeground = appForeground.foreground.value
        val idleNow = status is LocalRuntimeStatus.Ready && !hasActiveWork && !appInForeground
        val idleForMillis = idleTracker.update(idleNow)

        if (!app.settings.localRuntimeIdleStopEnabled) return false
        if (!shouldStopIdleRuntime(status, hasActiveWork, appInForeground, idleForMillis, IDLE_STOP_TIMEOUT_MILLIS)) {
            return false
        }
        autoRestartEnabled = false
        
        
        
        
        
        
        
        
        
        
        
        scope.launch(Dispatchers.IO) {
            runtimeWork.withLease(RUNTIME_OPERATION_LEASE_TAG) {
                runIdleStopSequence(
                    markInProgress = app::setIdleStopInProgress,
                    stop = manager::stop,
                    onStopped = {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    },
                )
            }
        }
        return true
    }

    







    @Synchronized
    private fun syncWakeLock(
        status: LocalRuntimeStatus,
        hasActiveWork: Boolean,
    ) {
        if (shuttingDown || !localRuntimeNeedsWakeLock(status, hasActiveWork)) {
            releaseWakeLock()
            return
        }
        val lock =
            wakeLock ?: getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG)
                ?.apply { setReferenceCounted(false) }
                ?.also { wakeLock = it }
        runCatching { lock?.acquire(WAKELOCK_TIMEOUT_MILLIS) }
            .onFailure { error -> Log.w(TAG, "Could not hold the CPU awake", error) }
    }

    @Synchronized
    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        if (!lock.isHeld) return
        runCatching { lock.release() }
            .onFailure { error -> Log.w(TAG, "Could not let the CPU sleep", error) }
    }

    







    @Synchronized
    private fun shutDownWakeLock() {
        shuttingDown = true
        releaseWakeLock()
    }

    private fun notification(status: LocalRuntimeStatus): android.app.Notification {
        val openIntent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val stopIntent =
            PendingIntent.getService(
                this,
                1,
                Intent(this, LocalRuntimeService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val (title, text, indeterminate, progress) =
            when (status) {
                LocalRuntimeStatus.NotInstalled ->
                    NotificationState(
                        getString(R.string.app_name),
                        getString(R.string.notification_runtime_not_installed),
                    )
                
                
                
                LocalRuntimeStatus.AwaitingStoragePermission ->
                    NotificationState(
                        getString(R.string.notification_runtime_awaiting_permission),
                        getString(R.string.install_step_needing_storage_permission),
                    )
                is LocalRuntimeStatus.Installing ->
                    NotificationState(
                        getString(R.string.notification_runtime_setting_up),
                        status.step,
                        status.progress == null,
                        ((status.progress ?: 0f) * 100).toInt(),
                    )
                is LocalRuntimeStatus.Starting ->
                    NotificationState(
                        getString(R.string.notification_runtime_starting),
                        getString(R.string.capability_version, status.version),
                        true,
                    )
                is LocalRuntimeStatus.Updating ->
                    NotificationState(
                        getString(R.string.notification_runtime_updating),
                        status.step,
                        status.progress == null,
                        ((status.progress ?: 0f) * 100).toInt(),
                    )
                is LocalRuntimeStatus.Stopped ->
                    NotificationState(
                        getString(R.string.notification_runtime_stopped),
                        getString(R.string.capability_version, status.version),
                    )
                is LocalRuntimeStatus.Ready ->
                    NotificationState(
                        getString(R.string.notification_runtime_ready),
                        getString(R.string.notification_runtime_ready_detail, status.version, status.port),
                    )
                is LocalRuntimeStatus.Broken -> NotificationState(getString(R.string.notification_runtime_broken), status.reason)
                is LocalRuntimeStatus.UnsupportedAbi ->
                    NotificationState(
                        getString(R.string.notification_runtime_unsupported_device),
                        getString(R.string.unsupported_abi, status.abi),
                    )
            }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(
                status is LocalRuntimeStatus.Ready ||
                    status is LocalRuntimeStatus.Installing ||
                    status is LocalRuntimeStatus.Starting ||
                    status is LocalRuntimeStatus.Updating,
            )
            .setOnlyAlertOnce(true)
            .setProgress(if (indeterminate || progress > 0) 100 else 0, progress, indeterminate)
            .addAction(0, getString(R.string.stop_run), stopIntent)
            .build()
    }

    private fun createChannel() {
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.local_runtime_screen_title),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notification_channel_local_runtime_description)
            }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private data class NotificationState(
        val title: String,
        val text: String,
        val indeterminate: Boolean = false,
        val progress: Int = 0,
    )

    companion object {
        private const val TAG = "LocalRuntimeService"
        private const val CHANNEL_ID = "local_opencode_runtime"
        private const val NOTIFICATION_ID = 4107
        private const val WAKELOCK_TAG = "opencode:runtime"
        private const val WAKELOCK_TIMEOUT_MILLIS = 10 * 60 * 1000L
        private const val RUNTIME_OPERATION_LEASE_TAG = "runtime-op"
        const val ACTION_INSTALL_AND_START = "com.tianercode.opencode.local.INSTALL_AND_START"
        const val ACTION_START = "com.tianercode.opencode.local.START"
        const val ACTION_STOP = "com.tianercode.opencode.local.STOP"
        const val ACTION_RESTART = "com.tianercode.opencode.local.RESTART"
        const val ACTION_REINSTALL = "com.tianercode.opencode.local.REINSTALL"
        const val ACTION_UPDATE = "com.tianercode.opencode.local.UPDATE"
        const val ACTION_ROLLBACK = "com.tianercode.opencode.local.ROLLBACK"
        const val ACTION_DELETE = "com.tianercode.opencode.local.DELETE"

        
        const val EXTRA_AGENTS = "com.tianercode.opencode.local.AGENTS"

        








        fun send(
            context: Context,
            action: String,
            agents: Set<LocalAgent> = emptySet(),
        ) {
            val intent = Intent(context, LocalRuntimeService::class.java).setAction(action)
            if (agents.isNotEmpty()) intent.putExtra(EXTRA_AGENTS, agents.map(LocalAgent::id).toTypedArray())
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { error -> Log.w(TAG, "Foreground start refused for $action", error) }
        }
    }
}

class LocalRuntimeServiceController(private val context: Context) {
    




    fun installAndStart(
        agents: Set<LocalAgent> = setOf(LocalAgent.OPEN_CODE),
    ) = LocalRuntimeService.send(
        context,
        LocalRuntimeService.ACTION_INSTALL_AND_START,
        agents,
    )

    fun start() = LocalRuntimeService.send(context, LocalRuntimeService.ACTION_START)

    fun stop() = LocalRuntimeService.send(context, LocalRuntimeService.ACTION_STOP)

    fun restart() = LocalRuntimeService.send(context, LocalRuntimeService.ACTION_RESTART)

    fun reinstall() = LocalRuntimeService.send(context, LocalRuntimeService.ACTION_REINSTALL)

    fun update() = LocalRuntimeService.send(context, LocalRuntimeService.ACTION_UPDATE)

    fun rollback() = LocalRuntimeService.send(context, LocalRuntimeService.ACTION_ROLLBACK)

    fun delete() = LocalRuntimeService.send(context, LocalRuntimeService.ACTION_DELETE)
}
