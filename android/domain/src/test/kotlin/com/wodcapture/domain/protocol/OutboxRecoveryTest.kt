package com.wodcapture.domain.protocol

import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class OutboxRecoveryTest {
    private val target = WatchTarget("watch", Protocol.APP_UUID_PRODUCTION, 1)
    private val envelope = Envelope(
        t = MessageType.PROFILE_PUT, id = "10000000-0000-4000-8000-000000000001",
        ts = 1, g = 1, oid = "profile", rev = 1, p = JsonObject(emptyMap()),
    )

    @Test
    fun restoredSendingProfileCanRetryWithIdenticalMessage() {
        val original = Outbox()
        original.enqueue(target, envelope, 0)
        val first = original.next(target, 100)!!
        val restored = Outbox(original.snapshot())
        val retry = assertNotNull(restored.next(target, 200))
        assertEquals(first.messageId, retry.messageId)
        assertEquals(first.body, retry.body)
    }

    @Test
    fun missingSdkCallbackUsesBoundedRetrySchedule() {
        val box = Outbox()
        box.enqueue(target, envelope, 0)
        val first = box.next(target, 0)!!
        assertEquals(35_000L, box.nextDueAt(target))
        assertNull(box.next(target, 34_999))
        listOf(35_000L, 80_000L, 170_000L).forEach { time ->
            val retry = assertNotNull(box.next(target, time))
            assertEquals(first.messageId, retry.messageId)
            assertEquals(first.body, retry.body)
        }
        assertNull(box.next(target, 260_000))
        assertEquals(DeliveryState.WAITING, box.snapshot().single().state)
    }

    @Test
    fun obsoleteRevisionsAreCompactedButLatestDeliveredAndFailedRevisionSurvive() {
        val box = Outbox()
        for (revision in 1..100) {
            val e = box.enqueue(target, envelope.copy(id = "message-$revision", rev = revision), revision.toLong())
            box.onAck(target, Ack(e.messageId, AckStatus.OK))
        }
        assertEquals(1, box.snapshot().size)
        assertEquals(100, box.latestPerObject(target).single().revision)
        val failed = box.enqueue(target, envelope.copy(id = "message-101", rev = 101), 101)
        box.next(target, 101)
        box.onSendStatus(failed.messageId, SendStatus.FAILURE_INVALID_FORMAT, 101)
        assertEquals(setOf(100, 101), box.snapshot().map { it.revision }.toSet())
        val restored = Outbox(box.snapshot())
        assertEquals(box.snapshot(), restored.snapshot())
        restored.requeue(target, "profile", 100)
        assertEquals(100, assertNotNull(restored.next(target, 102)).revision)
    }

    @Test
    fun offlineEditsRetainOnlyLatestPendingRevisionForEachTarget() {
        val box = Outbox()
        for (revision in 1..100) {
            box.enqueue(target, envelope.copy(id = "message-$revision", rev = revision), revision.toLong())
        }
        box.enqueue(target.copy(deviceId = "second"), envelope, 100)
        assertEquals(2, box.snapshot().size)
        assertEquals(100, box.latestPerObject(target).single().revision)
    }
}
