package com.inkforge.notesstudio

import org.junit.Assert.assertFalse
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

    @Test fun malformedVersionsAreRejected() {
        for (value in listOf("", "4.1", "4.1.1.2", "4.x.1", "04.1.1", "4.1.1+build", "4.1.1-beta.1", "4.999999999999999999999999.1")) {
            assertFalse("Invalid remote: $value", offer(value, "4.0.1"))
        }
        assertFalse(offer("4.1.1", "unknown"))
    }

    private fun offer(remote: String, installed: String) =
        AppUpdater.shouldOfferRelease(remote, installed, prerelease = false, draft = false)
}
