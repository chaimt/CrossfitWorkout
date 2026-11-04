package com.wodcapture.domain.parser

import com.wodcapture.domain.model.ParseResult

enum class JobState { QUEUED, RUNNING, READY, FAILED, CANCELLED }

/**
 * A user-requested parse (REQ-AX16, REQ-AX18). [draftRevision] is the draft revision the job was
 * started against; output for an older revision becomes a reviewable alternative.
 */
data class ParseJob(
    val jobId: String,
    val draftId: String,
    val draftRevision: Int,
    val state: JobState = JobState.QUEUED,
    val attempts: Int = 0,
    val error: ParseError? = null,
    /** Late output stored as an alternative instead of overwriting a manual edit. */
    val alternative: ParseResult? = null,
    val result: ParseResult? = null,
) {
    val terminal: Boolean get() = state == JobState.READY || state == JobState.FAILED || state == JobState.CANCELLED
}

/** Checks done at execution time (REQ-AX18). */
data class JobPreconditions(val cloudConsent: Boolean, val sourceExists: Boolean)

/** What the caller should do with a finished job's output. */
sealed interface JobCompletion {
    /** Apply the result to the draft (draft unchanged since the job started). */
    data class Apply(val result: ParseResult) : JobCompletion
    /** Draft was edited meanwhile: keep the output as an optional alternative. */
    data class Alternative(val result: ParseResult) : JobCompletion
    /** Cancelled/deleted/consent withdrawn: the late response is ignored. */
    data object Ignore : JobCompletion
}

/**
 * Pure parse-job lifecycle (REQ-AX18): queued → running → ready/failed/cancelled, at most
 * [MAX_ATTEMPTS] total attempts per user request, no auto-requeue for auth/refusal/schema/config
 * errors, manual Retry creates a new job.
 */
object ParseJobs {
    const val MAX_ATTEMPTS = 3

    /** Queues a job only with explicit Parse intent and current consent (REQ-AX16). */
    fun create(jobId: String, draftId: String, draftRevision: Int, consent: Boolean): ParseJob? =
        if (consent) ParseJob(jobId, draftId, draftRevision) else null

    /** Transition at execution time; rechecks consent, source existence and cancellation. */
    fun start(job: ParseJob, pre: JobPreconditions): ParseJob = when {
        job.state != JobState.QUEUED -> job
        !pre.cloudConsent -> job.copy(state = JobState.CANCELLED, error = ParseError.ConsentRequired)
        !pre.sourceExists -> job.copy(state = JobState.CANCELLED)
        job.attempts >= MAX_ATTEMPTS -> job.copy(state = JobState.FAILED)
        else -> job.copy(state = JobState.RUNNING)
    }

    /** Applies a parser outcome; [currentDraftRevision] detects manual edits during the parse. */
    fun complete(job: ParseJob, outcome: ParseOutcome, currentDraftRevision: Int?): Pair<ParseJob, JobCompletion> {
        if (job.state == JobState.CANCELLED || currentDraftRevision == null) return job to JobCompletion.Ignore
        val attempts = job.attempts + when (outcome) {
            is ParseOutcome.Success -> outcome.attempts
            is ParseOutcome.Failure -> outcome.attempts
        }
        return when (outcome) {
            is ParseOutcome.Success -> if (currentDraftRevision == job.draftRevision) {
                job.copy(state = JobState.READY, attempts = attempts, result = outcome.result) to JobCompletion.Apply(outcome.result)
            } else {
                job.copy(state = JobState.READY, attempts = attempts, alternative = outcome.result) to JobCompletion.Alternative(outcome.result)
            }
            is ParseOutcome.Failure -> {
                val requeue = outcome.error.retryable && attempts < MAX_ATTEMPTS
                job.copy(state = if (requeue) JobState.QUEUED else JobState.FAILED, attempts = attempts, error = outcome.error) to JobCompletion.Ignore
            }
        }
    }

    /** Cancel/delete/disable-cloud: prevents future sends; a submitted request's reply is ignored. */
    fun cancel(job: ParseJob): ParseJob = if (job.terminal) job else job.copy(state = JobState.CANCELLED)

    /** Whether a connectivity change may requeue this failed job automatically. */
    fun autoRequeueOnConnectivity(job: ParseJob): Boolean =
        job.state == JobState.QUEUED && (job.error?.retryable ?: true) && job.attempts < MAX_ATTEMPTS
}
