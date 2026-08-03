package com.dhaval.echo.data.backup.pipeline

import com.dhaval.echo.data.backup.BackupVerifier
import com.dhaval.echo.domain.backup.BackupFailure
import com.dhaval.echo.domain.backup.BackupProgress
import com.dhaval.echo.domain.backup.BackupStage
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Proves the archive is readable before anyone is told it exists.
 *
 * A backup that has not been read back is a hypothesis. This stage is what
 * turns it into a fact, and it is placed before [RetentionStage] on purpose: an
 * unverified archive must never be allowed to trigger the deletion of a
 * verified one.
 */
@Singleton
class VerifyStage @Inject constructor(
    private val verifier: BackupVerifier
) : BackupStageStep {

    override val stage = BackupStage.VERIFYING

    override suspend fun run(context: BackupContext): StageResult {
        val uri = context.sink?.uri ?: return StageResult.Failed(
            BackupFailure.Unexpected(IllegalStateException("Nothing was written"))
        )

        context.onProgress(BackupProgress(stage, bytesTotal = context.mediaBytes))

        val failure = verifier.verify(uri, context.password) { bytes ->
            context.onProgress(
                BackupProgress(stage, bytesDone = bytes, bytesTotal = context.mediaBytes)
            )
        }
        return failure?.let { StageResult.Failed(it) } ?: StageResult.Continue
    }
}
