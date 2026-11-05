package com.wodcapture.domain.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The fields WOD Capture reads from GitHub's "latest release" API response (REQ-AP07). */
@Serializable
data class GithubRelease(
    @SerialName("tag_name") val tagName: String,
    val name: String? = null,
    val body: String? = null,
    @SerialName("html_url") val htmlUrl: String = "",
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GithubAsset> = emptyList(),
)

/** A file attached to a release. */
@Serializable
data class GithubAsset(
    val name: String,
    @SerialName("browser_download_url") val downloadUrl: String,
    val size: Long = 0,
)

/** A newer WOD Capture build the user can install. */
data class AvailableUpdate(
    /** versionName of the release, e.g. "0.7.0". */
    val version: String,
    val tag: String,
    /** Release notes (Markdown) as published. */
    val notes: String,
    val apkUrl: String,
    val apkSize: Long,
    /** The release page, for "open in browser" when installing in-app is not possible. */
    val pageUrl: String,
)

/**
 * Decides whether a published release is an update (REQ-AP07). Releases come from
 * `.github/workflows/release.yml` (REQ-BT05): tag `v<versionName>`, one `.apk` asset. Pure and
 * network-free so it is unit-tested; fetching and installing live in the app module.
 */
object UpdateCheck {
    /** GitHub API: latest non-draft, non-pre-release release of the app's repository. */
    const val LATEST_RELEASE_URL = "https://api.github.com/repos/chaimt/CrossfitWorkout/releases/latest"

    private val json = Json { ignoreUnknownKeys = true }

    fun decode(body: String): GithubRelease = json.decodeFromString(GithubRelease.serializer(), body)

    /**
     * The update offered by [release] to an app at [currentVersion], or `null` when it is not
     * newer, is a draft or pre-release, has no APK, or its tag is not a version.
     */
    fun availableUpdate(release: GithubRelease, currentVersion: String): AvailableUpdate? {
        if (release.draft || release.prerelease) return null
        val apk = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) } ?: return null
        val version = release.tagName.removePrefix("v")
        if (!isNewer(version, currentVersion)) return null
        return AvailableUpdate(
            version = version,
            tag = release.tagName,
            notes = release.body.orEmpty().trim(),
            apkUrl = apk.downloadUrl,
            apkSize = apk.size,
            pageUrl = release.htmlUrl,
        )
    }

    /**
     * Only APKs served by GitHub over HTTPS are installed: github.com release links and the
     * githubusercontent.com hosts they redirect to. Checked on the link and on the final URL.
     */
    fun isTrustedDownload(url: String): Boolean {
        val match = Regex("""^https://([^/:?#@]+)(?:[/?#].*)?$""", RegexOption.IGNORE_CASE).find(url.trim()) ?: return false
        val host = match.groupValues[1].lowercase()
        return host == "github.com" || host.endsWith(".github.com") || host.endsWith(".githubusercontent.com")
    }

    /**
     * True when [candidate] is a higher dotted version than [current] ("0.10.0" > "0.9.3").
     * Suffixes after `-` or `+` (pre-release builds) are ignored; unparsable versions never win.
     */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = parse(candidate) ?: return false
        val b = parse(current) ?: return true
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun parse(version: String): List<Int>? {
        val core = version.trim().removePrefix("v").substringBefore('-').substringBefore('+')
        if (core.isEmpty()) return null
        return core.split('.').map { it.toIntOrNull() ?: return null }
    }
}
