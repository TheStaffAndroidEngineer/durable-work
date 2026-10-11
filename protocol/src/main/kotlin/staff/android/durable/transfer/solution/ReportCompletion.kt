package staff.android.durable.transfer.solution

import staff.android.durable.transfer.*

fun applyReportCompletion(
    current: ReportJob,
    authority: ReportAuthority?,
    attempt: ReportAttempt,
    outcome: ReportOutcome,
): ReportJob {
    if (authority == null || !authority.active) return current
    if (authority.accountId != current.accountId ||
        authority.generation != current.authorityGeneration
    ) return current
    if (current.phase != ReportPhase.Uploading) return current
    if (attempt.reportId != current.reportId ||
        attempt.accountId != current.accountId ||
        attempt.authorityGeneration != current.authorityGeneration ||
        attempt.contentGeneration != current.contentGeneration ||
        attempt.attemptId != current.attemptId
    ) return current
    return current.copy(
        phase = when (outcome) {
            ReportOutcome.Success -> ReportPhase.Complete
            ReportOutcome.Retryable -> ReportPhase.Queued
            ReportOutcome.Permanent -> ReportPhase.NeedsAttention
        },
        attemptId = null,
    )
}
