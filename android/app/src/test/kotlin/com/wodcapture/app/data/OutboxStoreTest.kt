package com.wodcapture.app.data

import com.wodcapture.domain.protocol.DeliveryState
import com.wodcapture.domain.protocol.OutboxEntry
import com.wodcapture.domain.protocol.WatchTarget
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OutboxStoreTest {
    private val memory = MemoryDao()
    private val entry = OutboxEntry("message", WatchTarget("watch", "app", 1), "profile.put",
        "profile", 1, JsonObject(emptyMap()))

    @Test
    fun unchangedSnapshotsDoNotRewriteRowsAndChangedRowsSurviveReload() {
        val store = OutboxStore(memory.dao)
        val other = entry.copy(messageId = "other", objectId = "other")
        store.save(listOf(entry, other))
        assertEquals(2, memory.outboxWrites)
        store.save(listOf(entry, other))
        assertEquals(2, memory.outboxWrites)
        val delivered = entry.copy(state = DeliveryState.ON_WATCH)
        store.save(listOf(delivered, other))
        assertEquals(3, memory.outboxWrites)
        assertEquals(listOf(delivered, other), OutboxStore(memory.dao).load())
        store.save(listOf(delivered))
        assertEquals(listOf(delivered), OutboxStore(memory.dao).load())
        store.save(emptyList())
        assertTrue(memory.outbox.isEmpty())
    }

    @Test
    fun refusedWriteDoesNotAdvancePersistenceCache() {
        val store = OutboxStore(memory.dao)
        memory.failOutboxWrite = true
        assertTrue(runCatching { store.save(listOf(entry)) }.isFailure)
        memory.failOutboxWrite = false
        store.save(listOf(entry))
        assertEquals(listOf(entry), OutboxStore(memory.dao).load())
    }
}
