package com.tolgalen.mktdoc.work

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tolgalen.mktdoc.BuildConfig
import com.tolgalen.mktdoc.MainActivity
import com.tolgalen.mktdoc.data.CHANGELOG
import com.tolgalen.mktdoc.data.HARDWARE
import com.tolgalen.mktdoc.data.Updates
import com.tolgalen.mktdoc.MktdocApp
import com.tolgalen.mktdoc.R
import java.util.concurrent.TimeUnit

/** Checks every day whether the website still publishes the downloaded versions and warns if not. */
class VersionCheckWorker(private val ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = ctx.applicationContext as MktdocApp
        val lib = app.library; val prefs = app.prefs
        if (prefs.notifyAppUpdates.value) runCatching {
            val n = Updates.newer(Updates(ctx).fetch(), BuildConfig.VERSION_NAME)
            if (n != null && prefs.lastNotifiedAppVersion != n.version) { notifyAppUpdate(ctx, n.version); prefs.lastNotifiedAppVersion = n.version }
        }
        val (online, ok) = lib.onlineVersions()
        if (!ok) return Result.retry()
        val fresh = prefs.registerOnlineVersions(online.map { it.label })
        if (fresh.isNotEmpty() && prefs.notifyNewVersions.value) notifyNewVersions(ctx, fresh)
        if (lib.listLocal().isEmpty()) return Result.success()
        val gone = lib.refreshAvailability(online)
        if (gone.isNotEmpty()) notifyGone(ctx, gone.map { it.label })
        return Result.success()
    }

    companion object {
        private const val NAME = "version-check"

        fun schedule(ctx: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(ctx)
            if (!enabled) { wm.cancelUniqueWork(NAME); return }
            val req = PeriodicWorkRequestBuilder<VersionCheckWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        /** Shows an alert (if notifications are allowed) that opens the app when tapped. */
        fun alert(ctx: Context, id: Int, title: String, text: String) {
            if (Build_SDK_33() && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            Jobs.ensureChannels(ctx)
            ctx.getSystemService(NotificationManager::class.java).notify(id, NotificationCompat.Builder(ctx, Jobs.CH_ALERTS)
                .setSmallIcon(R.drawable.ic_notification).setContentTitle(title)
                .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text)).setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)).build())
        }

        /** Name shown for a version or collection ("RouterOS 7.26", "Hardware guides"). */
        fun labelName(ctx: Context, label: String) = when (label) {
            CHANGELOG -> ctx.getString(R.string.changelog_title)
            HARDWARE -> ctx.getString(R.string.hardware_title)
            else -> "RouterOS $label"
        }

        fun notifyGone(ctx: Context, labels: List<String>) =
            alert(ctx, 3, ctx.getString(R.string.notif_version_gone_title), ctx.getString(R.string.notif_version_gone, labels.joinToString(", ")))

        fun notifyNewVersions(ctx: Context, labels: List<String>) =
            alert(ctx, 4, ctx.getString(R.string.notif_new_version_title), ctx.getString(R.string.notif_new_version, labels.joinToString(", ") { labelName(ctx, it) }))

        fun notifyAppUpdate(ctx: Context, version: String) =
            alert(ctx, 5, ctx.getString(R.string.notif_app_update_title), ctx.getString(R.string.notif_app_update, version))

        fun notifyContentChanges(ctx: Context, changes: List<Pair<String, Int>>) =
            alert(ctx, 6, ctx.getString(R.string.notif_content_title),
                changes.joinToString("\n") { (l, n) -> ctx.getString(R.string.notif_content_line, labelName(ctx, l), n) })

        @Suppress("FunctionName")
        private fun Build_SDK_33() = android.os.Build.VERSION.SDK_INT >= 33
    }
}
