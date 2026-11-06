package com.wodcapture.app.data

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/** In-memory rows for exercising DAO transaction bodies without an Android runtime. */
class MemoryDao {
    val workouts = mutableMapOf<String, WorkoutEntity>()
    val jobs = mutableMapOf<String, ParseJobEntity>()
    val outbox = mutableMapOf<String, OutboxEntity>()
    var outboxWrites = 0
    var failOutboxWrite = false
    val dao: WodDao = Proxy.newProxyInstance(
        WodDao::class.java.classLoader, arrayOf(WodDao::class.java),
    ) { proxy, method, args ->
        val values = args ?: emptyArray()
        if (method.isDefault) {
            // The JVM test runtime supports this Java API; Android's compile stubs omit it.
            InvocationHandler::class.java.getMethod(
                "invokeDefault", Any::class.java, Method::class.java, Array<Any>::class.java,
            ).invoke(null, proxy, method, values)
        } else when (method.name) {
            "workout" -> workouts[values[0]]
            "job" -> jobs[values[0]]
            "upsertWorkout" -> (values[0] as WorkoutEntity).let { workouts[it.id] = it; Unit }
            "upsertJob" -> (values[0] as ParseJobEntity).let { jobs[it.jobId] = it; Unit }
            "outbox" -> outbox.values.toList()
            "upsertOutbox" -> (values[0] as OutboxEntity).let {
                check(!failOutboxWrite) { "Storage refused" }
                outbox[it.key] = it
                outboxWrites++
                Unit
            }
            "deleteOutbox" -> { outbox.remove(values[0]); Unit }
            else -> error("Unexpected DAO operation: ${method.name}")
        }
    } as WodDao
}
