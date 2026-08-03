package com.dhaval.echo.data.backup

import android.content.Context
import android.util.Log
import androidx.work.WorkManager
import com.dhaval.echo.data.intelligence.EmbeddingWorker
import com.dhaval.echo.data.intelligence.MemoryIntelligenceWorker
import com.dhaval.echo.data.intelligence.UnderstandingBackfillWorker
import com.dhaval.echo.data.intelligence.UnderstandingWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stops the rest of the app touching data while a restore is replacing it.
 *
 * This is not defensive politeness. Echo runs background work that reads the
 * database and the media directories on its own schedule — `UnderstandingWorker`,
 * `EmbeddingWorker`, `MemoryIntelligenceWorker`, the backfill. A restore
 * swaps the database file and renames whole media directories out from under
 * whatever is running. Without a hard stop, the plausible outcomes include a
 * worker holding an open handle to a database that no longer exists, a
 * half-restored graph being re-indexed as though it were real, and an
 * event-triggered backup faithfully archiving a diary caught mid-swap.
 *
 * Maintenance mode ends when the process restarts, which restore does anyway:
 * that is also the cleanest way to guarantee nothing is still holding a stale
 * file handle.
 */
@Singleton
class MaintenanceMode @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val _active = MutableStateFlow(false)

    /** Observed by the UI to explain why recording and search are unavailable. */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    val isActive: Boolean get() = _active.value

    /**
     * Cancelling rather than pausing is deliberate: WorkManager has no pause,
     * and these jobs are all idempotent and re-enqueued on demand. Losing a
     * queued re-index costs a background pass; letting one run during a restore
     * costs correctness.
     */
    fun enter() {
        if (_active.value) return
        _active.value = true
        Log.i(TAG, "Maintenance mode on — suspending background work")
        runCatching {
            val workManager = WorkManager.getInstance(context)
            // By tag, not by unique name: the intelligence workers are enqueued
            // as "understanding_<entryId>", one unique name per memory, so there
            // is no fixed name to cancel. WorkManager tags every request with
            // its worker class name, which is the one handle that covers all of
            // them however many are in flight.
            SUSPENDED_WORKERS.forEach { workManager.cancelAllWorkByTag(it.name) }
            SUSPENDED_UNIQUE_WORK.forEach { workManager.cancelUniqueWork(it) }
        }.onFailure { Log.w(TAG, "Could not suspend background work", it) }
    }

    /**
     * Only for a restore that was abandoned before commit. A completed restore
     * leaves maintenance mode by restarting the process.
     */
    fun exit() {
        if (!_active.value) return
        _active.value = false
        Log.i(TAG, "Maintenance mode off")
    }

    /** Guards every entry point that would read or write user data. */
    fun ensureAvailable() {
        check(!_active.value) { "Echo is restoring a backup" }
    }

    private companion object {
        const val TAG = "MaintenanceMode"

        /**
         * Everything that reads the database or the media directories in the
         * background. Referenced as classes rather than string tags so a rename
         * is a compile error instead of a worker that quietly keeps running
         * during restores.
         */
        val SUSPENDED_WORKERS = listOf(
            UnderstandingWorker::class.java,
            UnderstandingBackfillWorker::class.java,
            MemoryIntelligenceWorker::class.java,
            EmbeddingWorker::class.java
        )

        /** Backup's own work, which does have fixed unique names. */
        val SUSPENDED_UNIQUE_WORK = listOf(
            BackupScheduler.PERIODIC_WORK_NAME,
            BackupScheduler.EVENT_WORK_NAME
        )
    }
}
