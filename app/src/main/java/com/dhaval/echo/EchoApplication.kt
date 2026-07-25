package com.dhaval.echo

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.dhaval.echo.data.intelligence.UnderstandingBackfillWorker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class EchoApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var sessionManager: com.dhaval.echo.data.auth.SessionManager

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        // One-time self-heal: rebuild the entity graph (→ Worlds), refresh
        // entity-based tags, auto-create Collections and re-link memories for
        // anything captured before these landed. Version-gated so it runs once
        // per upgrade, not every launch; the worker itself is offline + idempotent.
        val prefs = getSharedPreferences("echo_maintenance", MODE_PRIVATE)
        if (prefs.getInt(KEY_BACKFILL_VERSION, 0) < BACKFILL_VERSION) {
            UnderstandingBackfillWorker.enqueue(this)
            prefs.edit().putInt(KEY_BACKFILL_VERSION, BACKFILL_VERSION).apply()
        }
    }

    private companion object {
        const val KEY_BACKFILL_VERSION = "backfill_version"
        /** Bump to re-run the backfill after changing what it does. */
        const val BACKFILL_VERSION = 3
    }
}
