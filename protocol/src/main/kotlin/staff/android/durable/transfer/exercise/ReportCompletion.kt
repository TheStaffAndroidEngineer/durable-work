package staff.android.durable.transfer.exercise

import staff.android.durable.transfer.*

// Deliberately incomplete: a response is not enough authority.
fun applyReportCompletion(
    current: ReportJob,
    authority: ReportAuthority?,
    attempt: ReportAttempt,
    outcome: ReportOutcome,
): ReportJob = current.copy(
    phase = when (outcome) {
        ReportOutcome.Success -> ReportPhase.Complete
        ReportOutcome.Retryable -> ReportPhase.Queued
        ReportOutcome.Permanent -> ReportPhase.NeedsAttention
    },
    attemptId = null,
)
