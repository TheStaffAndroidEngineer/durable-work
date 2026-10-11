package staff.android.durable.transfer

data class ReportAuthority(
    val accountId: String,
    val generation: Long,
    val active: Boolean,
) {
    init {
        require(accountId.isNotBlank())
        require(generation >= 0)
    }
}

enum class ReportPhase {
    Queued, Uploading, Complete, Cancelled, NeedsAttention,
}

data class ReportJob(
    val reportId: String,
    val accountId: String,
    val authorityGeneration: Long,
    val contentGeneration: Long,
    val body: String,
    val phase: ReportPhase,
    val attemptId: String?,
) {
    init {
        require(reportId.isNotBlank() && accountId.isNotBlank())
        require(authorityGeneration >= 0 && contentGeneration >= 0)
        require((phase == ReportPhase.Uploading) == (attemptId != null))
        require(attemptId == null || attemptId.isNotBlank())
    }
}

data class ReportAttempt(
    val reportId: String,
    val accountId: String,
    val authorityGeneration: Long,
    val contentGeneration: Long,
    val attemptId: String,
) {
    init {
        require(reportId.isNotBlank() && accountId.isNotBlank())
        require(authorityGeneration >= 0 && contentGeneration >= 0)
        require(attemptId.isNotBlank())
    }
}

enum class ReportOutcome { Success, Retryable, Permanent }
