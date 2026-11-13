package com.wodcapture.app.ciq

import com.wodcapture.domain.protocol.Protocol
import com.wodcapture.domain.protocol.WatchTarget
import org.junit.Assert.*
import org.junit.Test

class WatchIdentityTest {
    @Test fun sdkCallbackIdentifiesTheSameQueueAsTheSelectedBetaBuild() {
        val queued = WatchTarget("watch", Protocol.APP_UUID_BETA, 2)
        val callback = sdkWatchTarget("watch", "A930ECF2-337F-4023-A7B4-46CE221A73CF", 2)
        assertEquals(queued, callback)
        assertEquals(queued.key, callback.key)
        assertNotEquals(WatchTarget("watch", Protocol.APP_UUID_PRODUCTION, 2), callback)
    }

    @Test fun deviceRefreshRetainsStoreInstallInfoAndUpdatesConnection() {
        val detected = WatchDevice("watch", "Old name", "DISCONNECTED",
            apps = mapOf(Protocol.APP_UUID_BETA to AppInstall.INSTALLED),
            appVersions = mapOf(Protocol.APP_UUID_BETA to 100),
            appErrors = mapOf(Protocol.APP_UUID_PRODUCTION to "ServiceUnavailableException"))
        val refreshed = mergeDiscoveredDevice(detected, WatchDevice("watch", "Forerunner 965", "CONNECTED"))
        assertEquals("CONNECTED", refreshed.status)
        assertEquals("Forerunner 965", refreshed.name)
        assertEquals(AppInstall.INSTALLED, refreshed.apps[Protocol.APP_UUID_BETA])
        assertEquals(detected.appVersions, refreshed.appVersions)
        assertEquals(detected.appErrors, refreshed.appErrors)
    }

    @Test fun helloVersionIsScopedToBuildAndSurvivesDiscoveryRefresh() {
        val device = WatchDevice("watch", "Forerunner 965", "CONNECTED",
            appVersions = mapOf(Protocol.APP_UUID_BETA to 100),
            appErrors = mapOf(Protocol.APP_UUID_BETA to "ServiceUnavailableException"))
        val reported = device.withReportedAppVersion(Protocol.APP_UUID_BETA, "0.1.0")
        val refreshed = mergeDiscoveredDevice(reported, WatchDevice("watch", "Forerunner 965", "DISCONNECTED"))
        assertEquals("Version 0.1.0", refreshed.appVersionText(Protocol.APP_UUID_BETA))
        assertEquals(AppInstall.INSTALLED, refreshed.apps[Protocol.APP_UUID_BETA])
        assertFalse(refreshed.appErrors.containsKey(Protocol.APP_UUID_BETA))
        assertFalse(refreshed.reportedAppVersions.containsKey(Protocol.APP_UUID_PRODUCTION))
        assertFalse(WatchDevice("other", "Other watch", "CONNECTED").reportedAppVersions.containsKey(Protocol.APP_UUID_BETA))
    }

    @Test fun sdkVersionCodeIsNotMisrepresentedAsSemanticVersion() {
        val device = WatchDevice("watch", "Watch", "CONNECTED",
            appVersions = mapOf(Protocol.APP_UUID_PRODUCTION to 100))
        assertEquals("Garmin version code: 100", device.appVersionText(Protocol.APP_UUID_PRODUCTION))
        assertEquals("Version unavailable — open WOD Coach and sync.", device.appVersionText(Protocol.APP_UUID_BETA))
    }
}
