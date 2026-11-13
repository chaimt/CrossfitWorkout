package com.wodcapture.app.data

import com.wodcapture.domain.protocol.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class SyncNoticesTest {
    private val target = WatchTarget("watch", Protocol.APP_UUID_PRODUCTION, 1)
    private fun entry(revision: Int, state: DeliveryState) = OutboxEntry(
        "message", target, MessageType.PLAN_PUT, "plan", revision,
        buildJsonObject { put("p", buildJsonObject { put("ti", "Friday workout") }) }, state,
    )

    @Test fun newAndUpdatedPlansAreNamedButOnlyAckedPlansAreConfirmed() {
        val queued = entry(1, DeliveryState.QUEUED)
        assertEquals(listOf("Queued new plan: Friday workout (rev 1)"), planSyncNotices(emptyList(), listOf(queued)))
        val sent = queued.copy(state = DeliveryState.SENT)
        assertEquals(listOf("Sent new plan: Friday workout (rev 1) — waiting for watch confirmation"), planSyncNotices(listOf(queued), listOf(sent)))
        assertEquals(listOf("Plan sent to watch: Friday workout (rev 1)"), planSyncNotices(listOf(sent), listOf(sent.copy(state = DeliveryState.ON_WATCH))))
        val updated = entry(2, DeliveryState.SENDING)
        assertEquals(listOf("Sending updated plan: Friday workout (rev 2)"), planSyncNotices(emptyList(), listOf(updated)))
        assertEquals(listOf("Plan updated on watch: Friday workout (rev 2)"), planSyncNotices(listOf(updated), listOf(updated.copy(state = DeliveryState.ON_WATCH))))
    }

    @Test fun duplicateSnapshotsDoNotRepeatLogsAndTargetsStaySeparate() {
        val sent = entry(2, DeliveryState.SENT)
        assertTrue(planSyncNotices(listOf(sent), listOf(sent.copy(sentAtMs = 100))).isEmpty())
        assertEquals(1, planSyncNotices(listOf(sent), listOf(sent.copy(target = target.copy(deviceId = "other")))).size)
        assertTrue(planSyncNotices(emptyList(), listOf(sent.copy(type = MessageType.PROFILE_PUT))).isEmpty())
    }

    @Test fun failedPlanIncludesTheReasonWithoutClaimingDelivery() {
        assertEquals(listOf("Plan sync failed: Friday workout (rev 2): size"),
            planSyncNotices(emptyList(), listOf(entry(2, DeliveryState.FAILED).copy(reason = "size"))))
    }
}
