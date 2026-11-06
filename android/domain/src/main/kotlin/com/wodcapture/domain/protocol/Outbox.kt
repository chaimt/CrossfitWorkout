package com.wodcapture.domain.protocol

import kotlinx.serialization.json.JsonObject

/**
 * Delivery target (REQ-SY11): pairing generation + device + watch app UUID. Production and beta
 * UUIDs are separate targets; re-pairing never migrates a queue.
 */
data class WatchTarget(val deviceId: String, val appUuid: String, val generation: Int) {
    val key: String get() = "$generation/$deviceId/$appUuid"
}

/** Status reported by the Connect IQ SDK for one `sendMessage` (mirrors `IQMessageStatus`). */
enum class SendStatus {
    SUCCESS,
    FAILURE_UNKNOWN,
    FAILURE_INVALID_FORMAT,
    FAILURE_MESSAGE_TOO_LARGE,
    FAILURE_UNSUPPORTED_TYPE,
    FAILURE_DURING_TRANSFER,
    FAILURE_INVALID_DEVICE,
    FAILURE_DEVICE_NOT_CONNECTED,
}

/** Visible per-object delivery state (REQ-AW02, REQ-AP06). */
enum class DeliveryState {
    /** Waiting for its turn (one outstanding data message per target). */
    QUEUED,
    /** Handed to the SDK; waiting for the send status. */
    SENDING,
    /** SDK accepted it; waiting for the watch's application ack (SDK success ≠ delivery). */
    SENT,
    /** Watch acked ok / already_applied. */
    ON_WATCH,
    /** Retry budget exhausted; waits for explicit Sync, hello or device-connected (REQ-SY13). */
    WAITING,
    /** Device not connected; resumes on reconnect. */
    SUSPENDED,
    /** Permanent error (`version`/`size`/`invalid`/`conflict`, too large); needs a fix. */
    FAILED,
    /** A newer revision replaced it before delivery. */
    SUPERSEDED,
    /** Watch holds a newer revision or tombstone. */
    STALE,
}

/** One durable outbox operation. Retries reuse [messageId] and [body] exactly (REQ-SY02). */
data class OutboxEntry(
    val messageId: String,
    val target: WatchTarget,
    val type: String,
    val objectId: String,
    val revision: Int,
    val body: JsonObject,
    val state: DeliveryState = DeliveryState.QUEUED,
    val retries: Int = 0,
    val sentAtMs: Long? = null,
    val reason: String? = null,
    val enqueuedAtMs: Long = 0,
)

/**
 * Phone → watch outbox state machine (REQ-SY04, SY11, SY13, SY14). Pure: the caller persists
 * [snapshot] after every mutation (it survives process death) and supplies the clock.
 */
class Outbox(entries: List<OutboxEntry> = emptyList()) {
    // SDK callbacks cannot survive process death. Retry the original wire message.
    private val items = entries.map {
        if (it.state == DeliveryState.SENDING) it.copy(state = DeliveryState.QUEUED, sentAtMs = null) else it
    }.toMutableList()
    private val lastAutoRestart = mutableMapOf<String, Long>()

    init {
        compact()
    }

    fun snapshot(): List<OutboxEntry> = items.toList()

    /**
     * Queues [envelope] for [target]. Older queued revisions of the same object are superseded;
     * an older revision than one already queued/delivered is refused (never send older, REQ-SY11).
     * Returns the entry that will be delivered.
     */
    fun enqueue(target: WatchTarget, envelope: Envelope, nowMs: Long): OutboxEntry {
        val oid = requireNotNull(envelope.oid) { "Data messages need an object id" }
        val rev = requireNotNull(envelope.rev)
        val body = EnvelopeCodec.toJson(envelope)
        val same = items.filter { it.target == target && it.objectId == oid }
        same.firstOrNull { it.revision > rev && it.state != DeliveryState.FAILED }?.let {
            throw IllegalStateException("Revision $rev of $oid is older than queued/delivered revision ${it.revision}")
        }
        same.firstOrNull { it.revision == rev && it.type == envelope.t && it.state != DeliveryState.FAILED }?.let { existing ->
            if (existing.body == body) return existing
            throw IllegalStateException("Revision $rev of $oid already exists with different content; bump the revision")
        }
        same.filter { it.state in PENDING }.forEach { replace(it.copy(state = DeliveryState.SUPERSEDED)) }
        val entry = OutboxEntry(envelope.id, target, envelope.t, oid, rev, body, enqueuedAtMs = nowMs)
        items += entry
        compact()
        return entry
    }

    /**
     * Next message to hand to the SDK for [target], or `null`. Enforces one outstanding data
     * message, the 30 s ack timeout and the 5/15/60 s retry schedule (max 3 per drain).
     */
    fun next(target: WatchTarget, nowMs: Long): OutboxEntry? {
        val inFlight = items.firstOrNull { it.target == target && (it.state == DeliveryState.SENDING || it.state == DeliveryState.SENT) }
        if (inFlight != null) {
            val due = (inFlight.sentAtMs ?: nowMs) + ACK_TIMEOUT_MS + RETRY_DELAYS_MS[minOf(inFlight.retries, RETRY_DELAYS_MS.lastIndex)]
            if (nowMs < due) return null
            if (inFlight.retries >= MAX_RETRIES_PER_DRAIN) {
                replace(inFlight.copy(state = DeliveryState.WAITING, reason = "no_ack"))
                return null
            }
            return inFlight.copy(state = DeliveryState.SENDING, retries = inFlight.retries + 1, sentAtMs = nowMs).also(::replace)
        }
        if (items.any { it.target == target && (it.state == DeliveryState.WAITING || it.state == DeliveryState.SUSPENDED) }) return null
        val queued = items.firstOrNull { it.target == target && it.state == DeliveryState.QUEUED } ?: return null
        return queued.copy(state = DeliveryState.SENDING, sentAtMs = nowMs).also(::replace)
    }

    /** When the next retry check is due for [target] (for scheduling a timer), or `null`. */
    fun nextDueAt(target: WatchTarget): Long? = items.firstOrNull { it.target == target && it.state in setOf(DeliveryState.SENDING, DeliveryState.SENT) }
        ?.let { (it.sentAtMs ?: 0) + ACK_TIMEOUT_MS + RETRY_DELAYS_MS[minOf(it.retries, RETRY_DELAYS_MS.lastIndex)] }

    /** Maps every SDK send status to a delivery state (REQ-AP06). Success is not delivery. */
    fun onSendStatus(messageId: String, status: SendStatus, nowMs: Long) {
        val e = items.firstOrNull { it.messageId == messageId && it.state == DeliveryState.SENDING } ?: return
        replace(
            when (status) {
                SendStatus.SUCCESS -> e.copy(state = DeliveryState.SENT, sentAtMs = nowMs, reason = null)
                SendStatus.FAILURE_DEVICE_NOT_CONNECTED -> e.copy(state = DeliveryState.SUSPENDED, reason = "device_not_connected")
                SendStatus.FAILURE_MESSAGE_TOO_LARGE -> e.copy(state = DeliveryState.FAILED, reason = "size")
                SendStatus.FAILURE_INVALID_FORMAT, SendStatus.FAILURE_UNSUPPORTED_TYPE -> e.copy(state = DeliveryState.FAILED, reason = "invalid_format")
                SendStatus.FAILURE_INVALID_DEVICE -> e.copy(state = DeliveryState.FAILED, reason = "invalid_device")
                // Transient: treat like a lost ack so the normal retry schedule applies. The ADB
                // simulator also reports FAILURE_UNKNOWN after SUCCESS (REQ-SY08); acks decide.
                SendStatus.FAILURE_DURING_TRANSFER, SendStatus.FAILURE_UNKNOWN ->
                    e.copy(state = DeliveryState.SENT, sentAtMs = e.sentAtMs ?: nowMs, reason = status.name.lowercase())
            },
        )
    }

    /** Applies a watch ack for one of our messages. Returns the updated entry, if matched. */
    fun onAck(target: WatchTarget, ack: Ack): OutboxEntry? {
        val e = items.firstOrNull { it.target == target && it.messageId == ack.mid } ?: return null
        val updated = when (ack.st) {
            AckStatus.OK, AckStatus.ALREADY_APPLIED -> e.copy(state = DeliveryState.ON_WATCH, reason = null)
            AckStatus.STALE -> e.copy(state = DeliveryState.STALE, reason = ack.rs ?: "stale")
            AckStatus.CONFLICT -> e.copy(state = DeliveryState.FAILED, reason = "conflict")
            else -> when (ack.rs) {
                // Capacity is fixable on the watch (free history); retry on the next sync.
                "capacity" -> e.copy(state = DeliveryState.WAITING, reason = "capacity")
                else -> e.copy(state = DeliveryState.FAILED, reason = ack.rs ?: "error")
            }
        }
        replace(updated)
        compact()
        return updated
    }

    /**
     * New lifecycle event (hello, device connected) or explicit Sync: waiting/suspended entries go
     * back to the queue with a fresh retry budget. Automatic restarts are limited to one per
     * 5 minutes per target; explicit Sync is not (REQ-SY13).
     */
    fun restart(target: WatchTarget, nowMs: Long, explicit: Boolean): Boolean {
        if (!explicit) {
            val last = lastAutoRestart[target.key]
            if (last != null && nowMs - last < AUTO_RESTART_MS) return false
            lastAutoRestart[target.key] = nowMs
        }
        items.filter { it.target == target && (it.state == DeliveryState.WAITING || it.state == DeliveryState.SUSPENDED || it.state == DeliveryState.SENT) }
            .forEach { replace(it.copy(state = DeliveryState.QUEUED, retries = 0, sentAtMs = null)) }
        return true
    }

    /** Re-queues an already delivered object the watch no longer reports (lost storage, REQ-SY05). */
    fun requeue(target: WatchTarget, objectId: String, revision: Int) {
        items.lastOrNull { it.target == target && it.objectId == objectId && it.revision == revision }
            ?.takeIf { it.state == DeliveryState.ON_WATCH || it.state in PENDING }
            ?.let { replace(it.copy(state = DeliveryState.QUEUED, retries = 0, sentAtMs = null)) }
    }

    /** Latest entry per object for [target] — the phone's view of what the watch should hold. */
    fun latestPerObject(target: WatchTarget): List<OutboxEntry> =
        items.filter { it.target == target }.groupBy { it.objectId }.values.map { l -> l.maxBy { it.revision } }

    /** Visible state of the latest revision of [objectId] on [target]. */
    fun stateOf(target: WatchTarget, objectId: String): OutboxEntry? =
        items.filter { it.target == target && it.objectId == objectId }.maxByOrNull { it.revision }

    /** Removes every queue of an old pairing generation (delete-all / re-pair, REQ-DM27). */
    fun dropGeneration(below: Int) {
        items.removeAll { it.target.generation < below }
    }

    private fun replace(e: OutboxEntry) {
        val i = items.indexOfFirst { it.messageId == e.messageId && it.target == e.target }
        if (i >= 0) items[i] = e else items += e
    }

    /** Keep reconciliation state and pending operations, not lifetime revision history. */
    private fun compact() {
        val keep = mutableSetOf<OutboxEntry>()
        items.groupBy { it.target to it.objectId }.values.forEach { revisions ->
            // Prefer the newest attempt when a failed revision was explicitly replaced.
            val newestFirst = revisions.asReversed()
            newestFirst.maxByOrNull { it.revision }?.let(keep::add)
            newestFirst.filter { it.state != DeliveryState.FAILED && it.state != DeliveryState.SUPERSEDED }
                .maxByOrNull { it.revision }?.let(keep::add)
            newestFirst.filter { it.state == DeliveryState.ON_WATCH }
                .maxByOrNull { it.revision }?.let(keep::add)
        }
        items.removeAll { it.state !in PENDING && it !in keep }
    }

    companion object {
        const val ACK_TIMEOUT_MS = 30_000L
        val RETRY_DELAYS_MS = longArrayOf(5_000, 15_000, 60_000)
        const val MAX_RETRIES_PER_DRAIN = 3
        const val AUTO_RESTART_MS = 5 * 60_000L
        private val PENDING = setOf(DeliveryState.QUEUED, DeliveryState.SENDING, DeliveryState.SENT, DeliveryState.WAITING, DeliveryState.SUSPENDED)
    }
}
