package com.wodcapture.domain.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Capacity refusals, same-revision identity and per-target retries (REQ-SY04, SY11, SY13). */
class OutboxCapacityTest {
    private val target = WatchTarget("watch", Protocol.APP_UUID_PRODUCTION, 1)
    private var n = 0
    private fun id() = "f0000000-0000-4000-8000-%012d".format(++n)
    private fun env(t: String, oid: String, rev: Int, value: Int? = 1) =
        Envelope(t = t, id = id(), ts = 1, g = 1, oid = oid, rev = rev, p = value?.let { JsonObject(mapOf("x" to JsonPrimitive(it))) })

    private fun deliverAndAck(ob: Outbox, now: Long, st: String, rs: String? = null): OutboxEntry {
        val e = ob.next(target, now)!!
        ob.onSendStatus(e.messageId, SendStatus.SUCCESS, now)
        ob.onAck(target, Ack(e.messageId, st, rs))
        return e
    }

    @Test
    fun aCapacityRefusalDoesNotBlockTheDeleteThatFreesSpace() {
        val ob = Outbox()
        val full = "a0000000-0000-4000-8000-000000000001"
        val old = "a0000000-0000-4000-8000-000000000002"
        ob.enqueue(target, env(MessageType.PLAN_PUT, full, 1), 0)
        deliverAndAck(ob, 0, AckStatus.ERROR, "capacity")
        assertEquals(DeliveryState.WAITING, ob.stateOf(target, full)!!.state)

        ob.enqueue(target, env(MessageType.PLAN_DEL, old, 2, value = null), 1)
        val del = deliverAndAck(ob, 2, AckStatus.OK)
        assertEquals(old, del.objectId, "the delete goes out despite the waiting plan")

        // The freed slot gives the refused plan another try straight away.
        assertEquals(full, ob.next(target, 3)!!.objectId)
    }

    @Test
    fun reenqueueingTheSamePayloadIsANoOpButDifferentContentStillNeedsABump() {
        val ob = Outbox()
        val oid = "a0000000-0000-4000-8000-000000000003"
        val first = ob.enqueue(target, env(MessageType.PROFILE_PUT, oid, 1), 0)
        assertSame(first, ob.enqueue(target, env(MessageType.PROFILE_PUT, oid, 1), 1))
        assertFailsWith<IllegalStateException> { ob.enqueue(target, env(MessageType.PROFILE_PUT, oid, 1, value = 2), 2) }
    }

    @Test
    fun versionMovesOnlyWhenTheQueueChanges() {
        val ob = Outbox()
        val v0 = ob.version
        assertEquals(null, ob.next(target, 0), "empty queue")
        assertEquals(v0, ob.version, "an idle tick changes nothing")
        ob.enqueue(target, env(MessageType.PLAN_PUT, "a0000000-0000-4000-8000-000000000006", 1), 0)
        val queued = ob.version
        assertTrue(queued > v0)
        val sent = ob.next(target, 0)!!
        val inFlight = ob.version
        assertTrue(inFlight > queued, "handing a message to the SDK is a change")
        assertEquals(null, ob.next(target, 1_000), "still waiting for the ack")
        assertEquals(inFlight, ob.version)
        ob.onSendStatus(sent.messageId, SendStatus.SUCCESS, 1_000)
        assertTrue(ob.version > inFlight)
    }

    @Test
    fun pendingTargetsCoverEveryTargetWithUndeliveredWork() {
        val ob = Outbox()
        val beta = target.copy(appUuid = Protocol.APP_UUID_BETA)
        ob.enqueue(target, env(MessageType.PLAN_PUT, "a0000000-0000-4000-8000-000000000004", 1), 0)
        ob.enqueue(beta, env(MessageType.PLAN_PUT, "a0000000-0000-4000-8000-000000000005", 1), 0)
        assertEquals(setOf(target, beta), ob.pendingTargets())
        deliverAndAck(ob, 1, AckStatus.OK)
        assertTrue(target !in ob.pendingTargets())
    }
}
