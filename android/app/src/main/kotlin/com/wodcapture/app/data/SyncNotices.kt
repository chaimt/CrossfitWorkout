package com.wodcapture.app.data

import com.wodcapture.domain.protocol.DeliveryState
import com.wodcapture.domain.protocol.MessageType
import com.wodcapture.domain.protocol.OutboxEntry
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Log actual transport transitions; SDK send success alone is not watch confirmation. */
fun planSyncNotices(before: List<OutboxEntry>, after: List<OutboxEntry>): List<String> {
    val previous = before.associateBy { it.target to it.messageId }
    return after.mapNotNull { entry ->
        if (entry.type != MessageType.PLAN_PUT) return@mapNotNull null
        val old = previous[entry.target to entry.messageId]
        if (old?.state == entry.state && old.reason == entry.reason) return@mapNotNull null
        val payload = entry.body["p"] as? JsonObject
        val title = (payload?.get("ti") as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            ?: "Plan ${entry.objectId.take(8)}"
        val plan = "$title (rev ${entry.revision})"
        val kind = if (entry.revision > 1) "updated plan" else "new plan"
        val reason = entry.reason?.let { ": $it" }.orEmpty()
        when (entry.state) {
            DeliveryState.QUEUED -> "Queued $kind: $plan"
            DeliveryState.SENDING -> "Sending $kind: $plan"
            DeliveryState.SENT -> "Sent $kind: $plan — waiting for watch confirmation"
            DeliveryState.ON_WATCH -> if (entry.revision > 1) "Plan updated on watch: $plan" else "Plan sent to watch: $plan"
            DeliveryState.WAITING -> "Plan waiting for retry: $plan$reason"
            DeliveryState.SUSPENDED -> "Plan waiting for connection: $plan$reason"
            DeliveryState.FAILED -> "Plan sync failed: $plan$reason"
            DeliveryState.STALE -> "Plan not applied: $plan$reason"
            DeliveryState.SUPERSEDED -> null
        }
    }
}

private val syncTimeFormat = DateTimeFormatter.ofPattern("HH:mm:ss")

/** Use the event timestamp, never the time when Compose redraws the log. */
fun syncLogTime(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    syncTimeFormat.format(Instant.ofEpochMilli(epochMs).atZone(zone))
