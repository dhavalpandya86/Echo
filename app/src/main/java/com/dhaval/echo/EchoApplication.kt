package com.dhaval.echo

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.dhaval.echo.data.intelligence.UnderstandingBackfillWorker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class EchoApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var sessionManager: com.dhaval.echo.data.auth.SessionManager

    @Inject
    lateinit var operationJournal: com.dhaval.echo.data.backup.OperationJournal

    @Inject
    lateinit var backupTriggers: com.dhaval.echo.data.backup.BackupTriggers

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()

        // Resolve anything a backup or restore left half-finished. This has to
        // happen at launch and nowhere else: a process killed mid-swap leaves
        // staging and holding directories that only a record of what was being
        // attempted can safely interpret, and that record is the journal.
        // Deliberately before any other work is queued, so nothing reads a
        // database that is still mid-restore.
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { operationJournal.recover() }
                .onFailure { Log.e("EchoApplication", "Backup recovery failed", it) }
        }
        // One-time self-heal: rebuild the entity graph (→ Worlds), refresh
        // entity-based tags, auto-create Collections and re-link memories for
        // anything captured before these landed. Version-gated so it runs once
        // per upgrade, not every launch; the worker itself is offline + idempotent.
        val prefs = getSharedPreferences("echo_maintenance", MODE_PRIVATE)
        if (prefs.getInt(KEY_BACKFILL_VERSION, 0) < BACKFILL_VERSION) {
            UnderstandingBackfillWorker.enqueue(this)
            prefs.edit().putInt(KEY_BACKFILL_VERSION, BACKFILL_VERSION).apply()
        }

        noticeAppUpdate(prefs)
    }

    /**
     * Archives the diary the first time a new build runs.
     *
     * The name of the trigger is "before app update", but an app cannot back
     * itself up before being replaced — by the time any Echo code runs, the
     * update has already happened. What is achievable, and what actually
     * protects the user, is capturing the state the *previous* version left
     * behind before the new one starts writing to it: migrations, re-indexing
     * and changed extractors all run after this point.
     */
    private fun noticeAppUpdate(prefs: android.content.SharedPreferences) {
        val current = currentVersionCode()
        if (current == 0L) return
        val previous = prefs.getLong(KEY_LAST_VERSION_CODE, 0L)
        prefs.edit().putLong(KEY_LAST_VERSION_CODE, current).apply()

        // Zero means a fresh install, which has nothing worth archiving yet.
        if (previous == 0L || previous >= current) return

        CoroutineScope(Dispatchers.IO).launch {
            runCatching { backupTriggers.onAppUpdated(previous, current) }
                .onFailure { Log.w("EchoApplication", "Post-update backup trigger failed", it) }
        }
    }

    /** BuildConfig isn't generated for this module, so ask the package manager. */
    private fun currentVersionCode(): Long = runCatching {
        val info = packageManager.getPackageInfo(packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION") info.versionCode.toLong()
        }
    }.getOrDefault(0L)

    private companion object {
        const val KEY_BACKFILL_VERSION = "backfill_version"
        const val KEY_LAST_VERSION_CODE = "last_version_code"
        /** Bump to re-run the backfill after changing what it does. */
        const val BACKFILL_VERSION = 3
    }
}
