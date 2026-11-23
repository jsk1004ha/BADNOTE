package com.inkforge.notesstudio

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdaterReleasePolicyTest {
    @Test fun betaAndDraftReleasesAreExcluded() {
        assertFalse(AppUpdater.shouldOfferRelease("4.1.1", "4.0.1", prerelease = true, draft = false))
        assertFalse(AppUpdater.shouldOfferRelease("4.1.1", "4.0.1", prerelease = false, draft = true))
        assertFalse(AppUpdater.shouldOfferRelease("4.1.1-beta.1", "4.0.1", prerelease = false, draft = false))
    }

    @Test fun stableUpgradeDoesNotOfferDowngradesOrTheInstalledRelease() {
        assertTrue(offer("v4.1.1", "4.0.1"))
        assertFalse(offer("4.0.1", "4.1.1-beta.1"))
        assertFalse(offer("4.1.1", "4.1.1"))
        assertFalse(offer("4.1.0", "4.1.1"))
        assertTrue(offer("4.2.0", "4.1.99"))
    }

    @Test fun betaCanUpgradeToTheStableReleaseWithTheSameCoreVersion() {
        assertTrue(offer("4.1.1", "4.1.1-beta.1"))
        assertTrue(offer("v4.1.1", "4.1.1-beta.2"))
        assertFalse(offer("4.1.1", "4.1.2-beta.1"))
    }

    @Test fun sameVersionBuildIsOfferedOnlyWhenTheBuildIncreases() {
        assertTrue(offer("v4.2.2+433", "4.2.2", 432))
        assertFalse(offer("v4.2.2+433", "4.2.2", 433))
        assertFalse(offer("v4.2.2+433", "4.2.3", 432))
        assertFalse(offer("v4.2.2+433", "4.2.2"))
        assertFalse(offer("v4.2.3+433", "4.2.2", 434))
        assertTrue(offer("v4.2.3", "4.2.2", 433))
        assertFalse(offer("v4.2.3", "4.2.3", 434))
    }

    @Test fun releaseMetadataSelectsTheInstalledVariantAndPreservesDigestAndNotes() {
        val api = release("v4.2.2+433", asset("SideBySide"), asset("Update"))
        val update = checkNotNull(AppUpdater.releaseFromJson(api, "4.2.2", 432, "update"))
        assertEquals("4.2.2", update.version)
        assertEquals(433, update.build)
        assertTrue(update.url.endsWith("-Update.apk"))
        assertEquals(83_517_679L, update.size)
        assertEquals("a".repeat(64), update.sha256)
        assertEquals("- Goodnotes import", update.notes)
        val parallel = checkNotNull(AppUpdater.releaseFromJson(api, "4.2.2", 432, "sideBySide"))
        assertTrue(parallel.url.endsWith("-SideBySide.apk"))
        assertNull(AppUpdater.releaseFromJson(api, "4.2.2", 433, "update"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun missingVariantIsACheckFailure() {
        AppUpdater.releaseFromJson(release("v4.2.2+433", asset("SideBySide")), "4.2.2", 432, "update")
    }

    @Test fun malformedVersionsAreRejected() {
        for (value in listOf("", "4.1", "4.1.1.2", "4.x.1", "04.1.1", "4.1.1+build", "4.1.1+0433",
            "4.1.1+2147483648", "4.1.1-beta.1", "4.999999999999999999999999.1")) {
            assertFalse("Invalid remote: $value", offer(value, "4.0.1"))
        }
        assertFalse(offer("4.1.1", "unknown"))
    }

    private fun offer(remote: String, installed: String, build: Int? = null) =
        AppUpdater.shouldOfferRelease(remote, installed, prerelease = false, draft = false, installedBuild = build)

    private fun release(tag: String, vararg assets: JSONObject) = JSONObject()
        .put("tag_name", tag).put("body", "- Goodnotes import").put("assets", JSONArray(assets.toList()))

    private fun asset(variant: String): JSONObject {
        val name = "bad-note-Android-4.2.2-$variant.apk"
        return JSONObject().put("name", name).put("state", "uploaded").put("size", 83_517_679)
            .put("digest", "sha256:" + "A".repeat(64))
            .put("browser_download_url", "https://github.com/jsk1004ha/BADNOTE/releases/download/v4.2.2%2B433/$name")
    }
}
