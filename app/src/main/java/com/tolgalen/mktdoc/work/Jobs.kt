package com.tolgalen.mktdoc.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.tolgalen.mktdoc.MainActivity
import com.tolgalen.mktdoc.R
import com.tolgalen.mktdoc.data.Reporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class JobState(
    val running: Boolean = false,
    /** There is something to show (running, or just finished and not dismissed). */
    val visible: Boolean = false,
    val title: String = "",
    val phase: String = "",
    val done: Int = 0,
    val total: Int = 0,
    val log: List<String> = emptyList(),
    val error: String? = null,
    val message: String? = null,
    val cancelled: Boolean = false,
    val token: Int = 0,
)

/** Long operations (downloads, checks, PDF, ZIP): they live in a foreground service. */
object Jobs {
    const val CH_PROGRESS = "progress"
    const val CH_ALERTS = "alerts"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(JobState())
    val state: StateFlow<JobState> = _state
    private var job: Job? = null
    private var counter = 0

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_PROGRESS, ctx.getString(R.string.channel_progress), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_ALERTS, ctx.getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_DEFAULT))
    }

    /** Starts an operation. Returns false if another one is already running. `block` returns the final message. */
    fun start(ctx: Context, title: String, block: suspend (Reporter) -> String?): Boolean {
        val app = ctx.applicationContext
        synchronized(this) {
            if (_state.value.running) return false
            _state.value = JobState(running = true, visible = true, title = title, token = ++counter)
        }
        ContextCompat.startForegroundService(app, Intent(app, JobService::class.java))
        val rep = Reporter(
            log = { line -> _state.value = _state.value.let { it.copy(log = (it.log + line).takeLast(150)) } },
            progress = { phase, d, t -> _state.value = _state.value.copy(phase = phase, done = d, total = t) },
        )
        job = scope.launch {
            try {
                val msg = block(rep)
                _state.value = _state.value.copy(running = false, message = msg)
            } catch (e: CancellationException) {
                _state.value = _state.value.copy(running = false, cancelled = true)
            } catch (e: Throwable) {
                _state.value = _state.value.copy(running = false, error = e.message ?: e.toString())
            }
        }
        return true
    }

    fun cancel() { job?.cancel() }

    fun dismiss() { if (!_state.value.running) _state.value = JobState() }

    fun log(line: String) { _state.value = _state.value.let { it.copy(log = (it.log + line).takeLast(150)) } }
}

class JobService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notif(s: JobState, ongoing: Boolean): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = NotificationCompat.Builder(this, Jobs.CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle(s.title.ifEmpty { getString(R.string.app_name) })
            .setContentText(phaseText(this, s)).setOngoing(ongoing).setOnlyAlertOnce(true).setContentIntent(open)
            .setProgress(s.total, s.done, s.total <= 0)
        if (ongoing) b.addAction(0, getString(R.string.cancel), PendingIntent.getService(this, 1,
            Intent(this, JobService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE))
        return b.build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) { Jobs.cancel(); return START_NOT_STICKY }
        Jobs.ensureChannels(this)
        startForeground(1, notif(Jobs.state.value, true), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        scope.launch {
            Jobs.state.collect { s ->
                if (s.running) {
                    getSystemService(NotificationManager::class.java).notify(1, notif(s, true))
                } else {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    val nm = getSystemService(NotificationManager::class.java)
                    val text = s.error?.let { getString(R.string.job_failed, it) } ?: if (s.cancelled) getString(R.string.job_cancelled) else s.message
                    if (text != null) nm.notify(2, NotificationCompat.Builder(this@JobService, Jobs.CH_ALERTS)
                        .setSmallIcon(R.drawable.ic_notification).setContentTitle(s.title).setContentText(text).setAutoCancel(true)
                        .setContentIntent(PendingIntent.getActivity(this@JobService, 0, Intent(this@JobService, MainActivity::class.java),
                            PendingIntent.FLAG_IMMUTABLE)).build())
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() { scope.coroutineContext[Job]?.cancel(); super.onDestroy() }

    companion object { const val ACTION_CANCEL = "cancel" }
}

fun phaseText(ctx: Context, s: JobState): String {
    val n = if (s.total > 0) " ${s.done}/${s.total}" else ""
    return when (s.phase) {
        "structure" -> ctx.getString(R.string.phase_structure)
        "pages" -> ctx.getString(R.string.phase_pages) + n
        "diff" -> ctx.getString(R.string.phase_diff)
        "render" -> ctx.getString(R.string.phase_render)
        "images" -> ctx.getString(R.string.phase_images)
        "print" -> ctx.getString(R.string.phase_print)
        "merge" -> ctx.getString(R.string.phase_merge)
        "zip" -> ctx.getString(R.string.phase_zip) + n
        else -> ctx.getString(R.string.phase_working)
    }
}
