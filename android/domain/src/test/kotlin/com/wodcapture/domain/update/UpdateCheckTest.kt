package com.wodcapture.domain.update

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateCheckTest {
    private val latest = """
        {
          "tag_name": "v0.7.0",
          "name": "WOD Capture 0.7.0",
          "body": "WOD Capture 0.7.0 (versionCode 7).\n\n## What's Changed\n* goal weight",
          "html_url": "https://github.com/chaimt/CrossfitWorkout/releases/tag/v0.7.0",
          "draft": false,
          "prerelease": false,
          "author": { "login": "github-actions[bot]" },
          "assets": [
            { "name": "notes.txt", "browser_download_url": "https://example.invalid/notes.txt", "size": 10 },
            { "name": "wod-capture-v0.7.0.apk", "browser_download_url": "https://example.invalid/wod.apk", "size": 41821456 }
          ]
        }
    """.trimIndent()

    @Test
    fun aNewerReleaseWithAnApkIsOffered() {
        val update = UpdateCheck.availableUpdate(UpdateCheck.decode(latest), currentVersion = "0.6.0")!!
        assertEquals("0.7.0", update.version)
        assertEquals("https://example.invalid/wod.apk", update.apkUrl)
        assertEquals(41821456, update.apkSize)
        assertTrue(update.notes.startsWith("WOD Capture 0.7.0"))
        assertEquals("https://github.com/chaimt/CrossfitWorkout/releases/tag/v0.7.0", update.pageUrl)
    }

    @Test
    fun theSameOrAnOlderReleaseIsNotOffered() {
        val release = UpdateCheck.decode(latest)
        assertNull(UpdateCheck.availableUpdate(release, "0.7.0"))
        assertNull(UpdateCheck.availableUpdate(release, "0.8.1"))
    }

    @Test
    fun draftsPreReleasesAndReleasesWithoutAnApkAreSkipped() {
        val release = UpdateCheck.decode(latest)
        assertNull(UpdateCheck.availableUpdate(release.copy(prerelease = true), "0.6.0"))
        assertNull(UpdateCheck.availableUpdate(release.copy(draft = true), "0.6.0"))
        assertNull(UpdateCheck.availableUpdate(release.copy(assets = release.assets.take(1)), "0.6.0"))
        assertNull(UpdateCheck.availableUpdate(release.copy(tagName = "nightly"), "0.6.0"))
    }

    @Test
    fun onlyGithubHttpsDownloadsAreTrusted() {
        assertTrue(UpdateCheck.isTrustedDownload("https://github.com/chaimt/CrossfitWorkout/releases/download/v0.7.0/wod-capture-v0.7.0.apk"))
        assertTrue(UpdateCheck.isTrustedDownload("https://objects.githubusercontent.com/github-production-release-asset/1?sig=x"))
        assertTrue(UpdateCheck.isTrustedDownload("https://release-assets.githubusercontent.com/x"))
        assertFalse(UpdateCheck.isTrustedDownload("http://github.com/chaimt/x.apk"))
        assertFalse(UpdateCheck.isTrustedDownload("https://github.com.evil.example/x.apk"))
        assertFalse(UpdateCheck.isTrustedDownload("https://evilgithub.com/x.apk"))
        assertFalse(UpdateCheck.isTrustedDownload("https://user@github.com/x.apk"))
        assertFalse(UpdateCheck.isTrustedDownload("not a url"))
    }

    @Test
    fun versionsCompareNumericallyPerPart() {
        assertTrue(UpdateCheck.isNewer("0.10.0", "0.9.3"))
        assertTrue(UpdateCheck.isNewer("v1.0", "0.99.99"))
        assertTrue(UpdateCheck.isNewer("0.6.1", "0.6"))
        assertFalse(UpdateCheck.isNewer("0.6.0", "0.6"))
        // A pre-release suffix does not make the same version newer.
        assertFalse(UpdateCheck.isNewer("0.6.0-1f0950c", "0.6.0"))
        assertFalse(UpdateCheck.isNewer("banana", "0.6.0"))
        assertTrue(UpdateCheck.isNewer("0.6.0", "dev"))
    }
}
