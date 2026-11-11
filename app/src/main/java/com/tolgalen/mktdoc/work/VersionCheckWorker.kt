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
import com.tolgalen.mktdoc.MainActivity
import com.tolgalen.mktdoc.MktdocApp
import com.tolgalen.mktdoc.R
import java.util.concurrent.TimeUnit

/** Checks every day whether the website still publishes the downloaded versions and warns if not. */
class VersionCheckWorker(private val ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val lib = (ctx.applicationContext as MktdocApp).library
        if (lib.listLocal().isEmpty()) return Result.success()
        val (online, ok) = lib.onlineVersions()
        if (!ok) return Result.retry()
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

        fun notifyGone(ctx: Context, labels: List<String>) {
            if (Build_SDK_33() && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            Jobs.ensureChannels(ctx)
            val text = ctx.getString(R.string.notif_version_gone, labels.joinToString(", "))
            ctx.getSystemService(NotificationManager::class.java).notify(3, NotificationCompat.Builder(ctx, Jobs.CH_ALERTS)
                .setSmallIcon(R.drawable.ic_notification).setContentTitle(ctx.getString(R.string.notif_version_gone_title))
                .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text)).setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)).build())
        }

        @Suppress("FunctionName")
        private fun Build_SDK_33() = android.os.Build.VERSION.SDK_INT >= 33
    }
}
