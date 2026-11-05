package com.wodcapture.app.update

import android.content.Context
import com.wodcapture.domain.update.AvailableUpdate
import com.wodcapture.domain.update.UpdateCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Finds and downloads WOD Capture updates published as GitHub Releases (REQ-AP07, REQ-BT05).
 * The repository is public, so no token is used; GitHub allows 60 such calls per hour per IP,
 * far more than one check per app open.
 */
class AppUpdater(private val context: Context) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val dir: File get() = File(context.cacheDir, "updates")

    /** versionName of the installed app, e.g. "0.6.0". */
    fun installedVersion(): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()

    /** The newer release, or `null` when the app is up to date or nothing is published yet. */
    suspend fun check(): AvailableUpdate? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(UpdateCheck.LATEST_RELEASE_URL)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()
        http.newCall(request).execute().use { response ->
            // 404 = no release published yet (or the repository is not public).
            if (response.code == 404) return@withContext null
            if (!response.isSuccessful) throw IOException("GitHub answered ${response.code}")
            val body = response.body?.string() ?: throw IOException("Empty answer from GitHub")
            UpdateCheck.availableUpdate(UpdateCheck.decode(body), installedVersion())
        }
    }

    /**
     * Downloads [update]'s APK into the cache, reporting (bytes done, bytes total) to
     * [onProgress]. A cancelled or failed download leaves no partial file behind.
     */
    suspend fun download(update: AvailableUpdate, onProgress: (Long, Long) -> Unit): File = withContext(Dispatchers.IO) {
        if (!UpdateCheck.isTrustedDownload(update.apkUrl)) throw IOException("Refusing to download from ${update.apkUrl}")
        dir.mkdirs()
        val target = File(dir, "wod-capture-${update.version}.apk")
        val partial = File(dir, "${target.name}.part")
        try {
            http.newCall(Request.Builder().url(update.apkUrl).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Download failed (${response.code})")
                if (!UpdateCheck.isTrustedDownload(response.request.url.toString())) throw IOException("Download was redirected off GitHub")
                val body = response.body ?: throw IOException("Empty download")
                val total = body.contentLength().takeIf { it > 0 } ?: update.apkSize
                body.byteStream().use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER)
                        var done = 0L
                        while (true) {
                            ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            done += n
                            onProgress(done, total)
                        }
                    }
                }
            }
            if (!partial.renameTo(target)) throw IOException("Could not store the download")
            target
        } catch (e: Throwable) {
            partial.delete()
            throw e
        }
    }

    /** Removes downloaded APKs (they are only needed until the install finishes). */
    fun clearDownloads() {
        dir.deleteRecursively()
    }

    private companion object {
        const val DEFAULT_BUFFER = 64 * 1024
    }
}
