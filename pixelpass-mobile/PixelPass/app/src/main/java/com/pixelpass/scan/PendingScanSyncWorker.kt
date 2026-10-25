package com.pixelpass.scan

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pixelpass.PixelPassApp
import com.pixelpass.core.PixelPassLog
import java.util.concurrent.TimeUnit

/**
 * Replays `pending_sync_scans` against `/api/scan` as soon as the phone has a
 * network again, so an offline approval reaches MongoDB without the guard doing
 * anything.
 */
class PendingScanSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? PixelPassApp ?: return Result.success()

        val report = app.container.scanRepository.syncPending()
        PixelPassLog.i(PixelPassLog.SYNC, "Sync run $runAttemptCount: $report")

        return if (report.retryable && runAttemptCount < MAX_ATTEMPTS) {
            Result.retry()
        } else {
            Result.success()
        }
    }

    companion object {
        /** WorkManager already backs off exponentially; cap the extra retries. */
        private const val MAX_ATTEMPTS = 5
    }
}

/**
 * Owns the two schedules used for the replay: a one-off nudge right after a scan
 * is queued and a 15 minute safety net that also covers scans left over from a
 * previous shift.
 */
object ScanSyncScheduler {

    private const val ONE_OFF_WORK = "pixelpass-scan-sync-now"
    private const val PERIODIC_WORK = "pixelpass-scan-sync-periodic"

    private fun networkConstraints(): Constraints =
        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Idempotent; called on every app start. */
    fun ensureScheduled(context: Context) {
        val request = PeriodicWorkRequestBuilder<PendingScanSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(networkConstraints())
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    /** Called by [ScanRepository] while it is still inside the scan request. */
    fun enqueue(context: Context) {
        val request = OneTimeWorkRequestBuilder<PendingScanSyncWorker>()
            .setConstraints(networkConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            ONE_OFF_WORK,
            ExistingWorkPolicy.KEEP,
            request
        )
    }
}
