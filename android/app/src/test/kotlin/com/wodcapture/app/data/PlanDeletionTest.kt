package com.wodcapture.app.data

import com.wodcapture.domain.protocol.*
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

class PlanDeletionTest {
    private val plan = PlanEntity("workout", "target", "plan", 2, 1, "payload")

    @Test fun deletionRetainsHighWaterMarkAndRepeatedDeletionDoesNotAdvanceIt() {
        val deleted = plan.deletion()
        assertEquals(3, deleted.revision)
        assertEquals("", deleted.planJson)
        assertEquals(deleted, deleted.deletion())
        assertEquals(plan.planId, deleted.planId)
    }

    @Test fun olderInstalledLineageRecoversDeletionFromDurableOutbox() {
        val deletion = OutboxEntry("message", WatchTarget("watch", "app", 1), MessageType.PLAN_DEL,
            plan.planId, 3, JsonObject(emptyMap()), state = DeliveryState.ON_WATCH)
        assertEquals(plan.deletion(), plan.reconciled(deletion))
        assertEquals(plan, plan.reconciled(deletion.copy(revision = 1)))
        assertEquals(plan, plan.reconciled(deletion.copy(type = MessageType.PLAN_PUT)))
    }
}
