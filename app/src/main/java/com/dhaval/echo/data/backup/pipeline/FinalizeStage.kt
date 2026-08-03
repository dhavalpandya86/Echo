package com.dhaval.echo.data.backup.pipeline

import com.dhaval.echo.data.backup.BackupCrypto
import com.dhaval.echo.data.backup.BackupIndex
import com.dhaval.echo.data.backup.BackupLocalState
import com.dhaval.echo.data.backup.OperationJournal
import com.dhaval.echo.domain.backup.BackupFailure
import com.dhaval.echo.domain.backup.BackupHeaderInfo
import com.dhaval.echo.domain.backup.BackupStage
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Records that the backup happened.
 *
 * Everything with a lasting effect is concentrated here, at the end: the health
 * state, the fingerprint that lets the next run skip, the history row, and
 * clearing the journal. Until this stage, a backup that dies leaves no trace
 * beyond a file the pipeline deletes — which is precisely why cancellation and
 * failure need no special handling anywhere upstream.
 */
@Singleton
class FinalizeStage @Inject constructor(
    private val localState: BackupLocalState,
    private val index: BackupIndex,
    private val journal: OperationJournal
) : BackupStageStep {

    override val stage = BackupStage.COMPLETE

    override suspend fun run(context: BackupContext): StageResult {
        val sink = context.sink ?: return StageResult.Failed(
            BackupFailure.Unexpected(IllegalStateException("Nothing was written"))
        )
        val manifest = context.manifest ?: return StageResult.Failed(
            BackupFailure.Unexpected(IllegalStateException("No manifest"))
        )

        val summary = manifest.toSummary(
            BackupHeaderInfo(
                fileName = context.fileName,
                sizeBytes = context.archiveSizeBytes,
                createdAtMillis = context.startedAt,
                encrypted = context.password != null,
                backupFormat = BackupCrypto.BACKUP_FORMAT
            )
        )
        context.summary = summary

        localState.recordSuccess(
            atMillis = context.startedAt,
            uri = sink.uri,
            sizeBytes = context.archiveSizeBytes,
            fingerprint = context.fingerprint
        )
        index.record(sink.uri, summary)
        journal.clear()

        // From here the archive is real and must not be cleaned up.
        context.committed = true
        return StageResult.Continue
    }
}
