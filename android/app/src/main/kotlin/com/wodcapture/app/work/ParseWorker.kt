package com.wodcapture.app.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.wodcapture.app.container
import com.wodcapture.app.data.ParseJobEntity
import com.wodcapture.domain.parser.CloudLlmParser
import com.wodcapture.domain.parser.CloudParserConfig
import com.wodcapture.domain.parser.ImageInput
import com.wodcapture.domain.parser.JobCompletion
import com.wodcapture.domain.parser.JobPreconditions
import com.wodcapture.domain.parser.JobState
import com.wodcapture.domain.parser.OkHttpTransport
import com.wodcapture.domain.parser.ParseError
import com.wodcapture.domain.parser.ParseInput
import com.wodcapture.domain.parser.ParseJob
import com.wodcapture.domain.parser.ParseJobs
import com.wodcapture.domain.parser.ParseOutcome
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Background cloud parse (REQ-AX16, REQ-AX18). WorkManager handles connectivity; this worker
 * rechecks consent, source and cancellation at execution and never sends anything to a watch.
 */
class ParseWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val c = applicationContext.container
        val dao = c.db.dao()
        val jobId = inputData.getString(KEY_JOB) ?: return Result.failure()
        val jobRow = dao.job(jobId) ?: return Result.failure()
        val draft = dao.workout(jobRow.draftId)
        val settings = c.settings.current()
        val images = draft?.let { Json.decodeFromString(ListSerializer(String.serializer()), it.imagePaths) }.orEmpty().map(::File)
        var job = ParseJob(jobRow.jobId, jobRow.draftId, jobRow.draftRevision, JobState.valueOf(jobRow.state), jobRow.attempts)
        job = ParseJobs.start(job, JobPreconditions(settings.cloudParsing && settings.consentGiven, draft != null && images.all { it.exists() }))
        save(job, jobRow)
        if (job.state != JobState.RUNNING) return Result.success()
        val key = c.secrets.apiKey() ?: return save(job.copy(state = JobState.FAILED, error = ParseError.InvalidCredentials), jobRow).let { Result.success() }

        val parser = CloudLlmParser(CloudParserConfig(apiKey = key, model = settings.model, effort = settings.effort), c.catalog, OkHttpTransport())
        val input = ParseInput(images = images.map { ImageInput(it.readBytes()) }, text = draft!!.rawText)
        val outcome = parser.parse(input) { isStopped || dao.job(jobId)?.state == JobState.CANCELLED.name }
        val current = dao.workout(jobRow.draftId)
        val (done, completion) = ParseJobs.complete(job, outcome, current?.revision)
        save(done, jobRow)
        val now = System.currentTimeMillis() / 1000
        when (completion) {
            is JobCompletion.Apply -> c.workouts.applyParse(current!!, completion.result, now)
            is JobCompletion.Alternative -> c.workouts.storeAlternative(current!!, completion.result)
            JobCompletion.Ignore -> current?.takeIf { done.state == JobState.FAILED }?.let { c.workouts.setStatus(it, "draft") }
        }
        return if (done.state == JobState.QUEUED && outcome is ParseOutcome.Failure && outcome.error.retryable) Result.retry() else Result.success()
    }

    private fun save(job: ParseJob, row: ParseJobEntity) {
        applicationContext.container.db.dao().upsertJob(row.copy(state = job.state.name, attempts = job.attempts, error = job.error?.toString()))
    }

    companion object {
        const val KEY_JOB = "job"

        /** Queues a parse after explicit Parse intent (REQ-AX16); unique per draft. */
        fun enqueue(context: Context, jobId: String, draftId: String) {
            val req = OneTimeWorkRequestBuilder<ParseWorker>()
                .setInputData(workDataOf(KEY_JOB to jobId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("parse-$draftId", ExistingWorkPolicy.REPLACE, req)
        }
    }
}
