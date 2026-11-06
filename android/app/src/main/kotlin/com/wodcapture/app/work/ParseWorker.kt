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
import com.wodcapture.domain.parser.CloudLlmParser
import com.wodcapture.domain.parser.CloudParserConfig
import com.wodcapture.domain.parser.ImageInput
import com.wodcapture.domain.parser.JobPreconditions
import com.wodcapture.domain.parser.JobState
import com.wodcapture.domain.parser.OkHttpTransport
import com.wodcapture.domain.parser.ParseError
import com.wodcapture.domain.parser.ParseInput
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
        val job = dao.startParse(jobId, JobPreconditions(settings.cloudParsing && settings.consentGiven, draft != null && !draft.archived && images.all { it.exists() }))
            ?: return Result.success()
        if (job.state != JobState.RUNNING.name) return Result.success()
        val key = c.secrets.apiKey() ?: run {
            dao.completeParse(jobId, ParseOutcome.Failure(ParseError.InvalidCredentials, 0), System.currentTimeMillis() / 1000, isStopped)
            return Result.success()
        }

        val parser = CloudLlmParser(CloudParserConfig(apiKey = key, model = settings.model, effort = settings.effort), c.catalog, OkHttpTransport())
        val input = ParseInput(images = images.map { ImageInput(it.readBytes()) }, text = draft!!.rawText)
        val outcome = parser.parse(input) { isStopped || dao.job(jobId)?.state != JobState.RUNNING.name }
        val done = dao.completeParse(jobId, outcome, System.currentTimeMillis() / 1000, isStopped)
        return if (done?.state == JobState.QUEUED && outcome is ParseOutcome.Failure && outcome.error.retryable) Result.retry() else Result.success()
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
