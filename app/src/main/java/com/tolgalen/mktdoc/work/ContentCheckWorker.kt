package com.tolgalen.mktdoc.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tolgalen.mktdoc.MktdocApp
import com.tolgalen.mktdoc.data.Reporter
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Once a week, on an unmetered network, compares each downloaded copy with the website and notifies when something
 * changed. The changes are only staged (as when the user taps "Check differences"); nothing is applied.
 */
class ContentCheckWorker(private val ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val lib = (ctx.applicationContext as MktdocApp).library
        if (Jobs.state.value.running) return Result.retry()          // the user is already running an operation
        val (online, ok) = lib.onlineVersions()
        val started = System.currentTimeMillis()
        val changed = ArrayList<Pair<String, Int>>()
        for (lv in lib.listLocal()) {
            if (lv.removedOnline || lv.pendingChanges > 0) continue    // already waiting for review
            if (System.currentTimeMillis() - started > BUDGET_MS) break // the rest next week (a worker may run ~10 min)
            try {
                val r = lib.check(lv.label, if (ok) online else null, Reporter())
                if (r.hasChanges && !r.unavailableOnline) changed.add(lv.label to r.changes.size)
            } catch (e: CancellationException) { throw e } catch (_: Exception) { /* offline or the site failed: try again next week */ }
        }
        if (changed.isNotEmpty()) VersionCheckWorker.notifyContentChanges(ctx, changed)
        return Result.success()
    }

    companion object {
        private const val NAME = "content-check"
        private const val BUDGET_MS = 8 * 60 * 1000L

        fun schedule(ctx: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(ctx)
            if (!enabled) { wm.cancelUniqueWork(NAME); return }
            val req = PeriodicWorkRequestBuilder<ContentCheckWorker>(7, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresBatteryNotLow(true).build()).build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
