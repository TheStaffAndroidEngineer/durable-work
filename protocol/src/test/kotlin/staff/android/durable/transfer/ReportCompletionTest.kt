package staff.android.durable.transfer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

abstract class ReportCompletionContract {
    abstract fun complete(
        current: ReportJob,
        authority: ReportAuthority?,
        attempt: ReportAttempt,
        outcome: ReportOutcome,
    ): ReportJob

    private val authority = ReportAuthority("account-a", 8, true)
    private val job = ReportJob(
        "north-gate", "account-a", 8, 7, "  Vérifier la serrure\n",
        ReportPhase.Uploading, "attempt-1",
    )
    private val attempt = ReportAttempt(
        job.reportId, job.accountId, 8, 7, "attempt-1",
    )

    @Test fun matchingSuccessCompletesAndPreservesExactContent() {
        assertEquals(
            job.copy(phase = ReportPhase.Complete, attemptId = null),
            complete(job, authority, attempt, ReportOutcome.Success),
        )
    }

    @Test fun matchingRetryableResultQueuesTheSameContent() {
        assertEquals(
            job.copy(phase = ReportPhase.Queued, attemptId = null),
            complete(job, authority, attempt, ReportOutcome.Retryable),
        )
    }

    @Test fun matchingPermanentResultRequiresAttention() {
        assertEquals(
            job.copy(phase = ReportPhase.NeedsAttention, attemptId = null),
            complete(job, authority, attempt, ReportOutcome.Permanent),
        )
    }

    @Test fun cancellationCannotBeUndoneByAnyLateOutcome() {
        val cancelled = job.copy(
            phase = ReportPhase.Cancelled, attemptId = null,
        )
        for (outcome in ReportOutcome.entries) {
            assertEquals(cancelled, complete(cancelled, authority, attempt, outcome))
        }
    }

    @Test fun replacedContentDoesNotBecomeCompleteWithItsOldAttempt() {
        val replacement = job.copy(
            contentGeneration = 8, body = "New report body",
        )
        assertEquals(
            replacement,
            complete(replacement, authority, attempt, ReportOutcome.Success),
        )
    }

    @Test fun newerAttemptOfTheSameContentRejectsTheOlderCompletion() {
        val newer = job.copy(attemptId = "attempt-2")
        assertEquals(
            newer, complete(newer, authority, attempt, ReportOutcome.Success),
        )
    }

    @Test fun anotherReportWithTheSameAttemptLabelIsIndependent() {
        val meter = job.copy(reportId = "meter-room", body = "Read meter")
        assertEquals(
            meter, complete(meter, authority, attempt, ReportOutcome.Success),
        )
    }

    @Test fun anAccountSwitchRejectsAnOldAccountResponse() {
        val other = job.copy(accountId = "account-b")
        val otherAuthority = authority.copy(accountId = "account-b")
        assertEquals(
            other, complete(other, otherAuthority, attempt, ReportOutcome.Success),
        )
    }

    @Test fun sameAccountReauthenticationRejectsOldGeneration() {
        val renewed = job.copy(authorityGeneration = 9)
        val newAuthority = authority.copy(generation = 9)
        assertEquals(
            renewed, complete(renewed, newAuthority, attempt, ReportOutcome.Success),
        )
    }

    @Test fun missingExpiredOrUnrelatedCurrentAuthorityPreservesTheRow() {
        for (currentAuthority in listOf(
            null, authority.copy(active = false),
            authority.copy(accountId = "account-b"),
            authority.copy(generation = 9),
        )) {
            assertEquals(
                job, complete(job, currentAuthority, attempt, ReportOutcome.Success),
            )
        }
    }

    @Test fun aDuplicateCompletionCannotRewriteATerminalState() {
        val completed = complete(job, authority, attempt, ReportOutcome.Success)
        assertEquals(ReportPhase.Complete, completed.phase)
        assertEquals(
            completed,
            complete(completed, authority, attempt, ReportOutcome.Retryable),
        )
    }

    @Test fun queuedAndAttentionRowsDoNotAcceptAnUnclaimedResult() {
        for (phase in listOf(ReportPhase.Queued, ReportPhase.NeedsAttention)) {
            val waiting = job.copy(phase = phase, attemptId = null)
            assertEquals(
                waiting, complete(waiting, authority, attempt, ReportOutcome.Success),
            )
        }
    }

    @Test fun aCurrentEmptyBodyIsPreservedExactly() {
        val empty = job.copy(body = "")
        assertEquals(
            empty.copy(phase = ReportPhase.Complete, attemptId = null),
            complete(empty, authority, attempt, ReportOutcome.Success),
        )
    }

    @Test fun invalidModelIdentitiesGenerationsAndPhasePairsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            authority.copy(accountId = " ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            authority.copy(generation = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            job.copy(reportId = "")
        }
        assertThrows(IllegalArgumentException::class.java) {
            job.copy(contentGeneration = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            job.copy(authorityGeneration = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            job.copy(attemptId = null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            job.copy(phase = ReportPhase.Queued)
        }
        assertThrows(IllegalArgumentException::class.java) {
            attempt.copy(attemptId = " ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            attempt.copy(contentGeneration = -1)
        }
    }
}

class ReportCompletionReferenceTest : ReportCompletionContract() {
    override fun complete(
        current: ReportJob, authority: ReportAuthority?,
        attempt: ReportAttempt, outcome: ReportOutcome,
    ) = staff.android.durable.transfer.solution.applyReportCompletion(
        current, authority, attempt, outcome,
    )
}

@Tag("exercise")
class ReportCompletionLearnerTest : ReportCompletionContract() {
    override fun complete(
        current: ReportJob, authority: ReportAuthority?,
        attempt: ReportAttempt, outcome: ReportOutcome,
    ) = staff.android.durable.transfer.exercise.applyReportCompletion(
        current, authority, attempt, outcome,
    )
}
