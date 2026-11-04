package com.wodcapture.domain.protocol

import com.wodcapture.domain.isWireUuid
import com.wodcapture.domain.model.WatchDefaults
import com.wodcapture.domain.plan.WatchLimits
import com.wodcapture.domain.plan.WatchPlan
import com.wodcapture.domain.plan.WireJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Ack statuses (protocol §3.1). */
object AckStatus {
    const val OK = "ok"
    const val ALREADY_APPLIED = "already_applied"
    const val STALE = "stale"
    const val CONFLICT = "conflict"
    const val ERROR = "error"
    val ALL = setOf(OK, ALREADY_APPLIED, STALE, CONFLICT, ERROR)
}

/** Ack payload `{mid, st, rs?, ix?}`. */
data class Ack(val mid: String, val st: String, val rs: String? = null, val ix: Int? = null) {
    fun toJson(): JsonObject = buildJsonObject {
        put("mid", mid)
        put("st", st)
        rs?.let { put("rs", it) }
        ix?.let { put("ix", it) }
    }

    companion object {
        fun parse(p: JsonObject): Ack {
            val mid = p.str("mid")?.takeIf(::isWireUuid) ?: throw WireException("invalid", "ack.mid")
            val st = p.str("st")?.takeIf { it in AckStatus.ALL } ?: throw WireException("invalid", "ack.st")
            val rs = p.str("rs")?.also { if (it.length > 32) throw WireException("invalid", "ack.rs too long") }
            val ix = p.int("ix")?.also { if (it < 0) throw WireException("invalid", "ack.ix") }
            requireOnly(p, setOf("mid", "st", "rs", "ix"), "ack")
            return Ack(mid, st, rs, ix)
        }
    }
}

/** A stored object `[id, rev]` in hello inventories. */
data class IdRev(val id: String, val rev: Int)

/** `hello` payload (protocol §3.2). */
data class Hello(
    val pv: List<Int>,
    val app: String,
    val dev: String,
    val inv: List<IdRev>,
    val del: List<IdRev>,
    val free: Long,
    val rc: Int,
    val cap: List<String>,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("pv", JsonArray(pv.map { JsonPrimitive(it) }))
        put("app", app)
        put("dev", dev)
        put("inv", idRevs(inv))
        put("del", idRevs(del))
        put("free", free)
        put("rc", rc)
        put("cap", JsonArray(cap.map { JsonPrimitive(it) }))
    }

    private fun idRevs(l: List<IdRev>) = JsonArray(l.map { buildJsonArray { add(JsonPrimitive(it.id)); add(JsonPrimitive(it.rev)) } })

    companion object {
        fun parse(p: JsonObject): Hello {
            requireOnly(p, setOf("pv", "app", "dev", "inv", "del", "free", "rc", "cap"), "hello")
            fun idRevs(k: String): List<IdRev> = arr(p, k).map { e ->
                val a = e as? JsonArray ?: throw WireException("invalid", "hello.$k entry")
                if (a.size != 2) throw WireException("invalid", "hello.$k entry size")
                val id = (a[0] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(::isWireUuid)
                    ?: throw WireException("invalid", "hello.$k id")
                val rev = (a[1] as? JsonPrimitive)?.content?.toIntOrNull()?.takeIf { it >= 1 }
                    ?: throw WireException("invalid", "hello.$k rev")
                IdRev(id, rev)
            }
            val pv = arr(p, "pv").map { (it as? JsonPrimitive)?.content?.toIntOrNull() ?: throw WireException("invalid", "hello.pv") }
            if (pv.isEmpty()) throw WireException("invalid", "hello.pv empty")
            val inv = idRevs("inv")
            if (inv.size > 10) throw WireException("invalid", "hello.inv > 10")
            return Hello(
                pv = pv,
                app = p.str("app") ?: throw WireException("invalid", "hello.app"),
                dev = p.str("dev") ?: throw WireException("invalid", "hello.dev"),
                inv = inv,
                del = idRevs("del"),
                free = p.long("free")?.takeIf { it >= 0 } ?: throw WireException("invalid", "hello.free"),
                rc = p.int("rc")?.takeIf { it >= 0 } ?: throw WireException("invalid", "hello.rc"),
                cap = arr(p, "cap").map { (it as? JsonPrimitive)?.takeIf { x -> x.isString }?.content ?: throw WireException("invalid", "hello.cap") },
            )
        }
    }
}

/** `result.chunk` payload (protocol §3.8, REQ-SY15). */
data class Chunk(val xid: String, val ix: Int, val n: Int, val len: Int, val sha: String, val d: String) {
    fun toJson(): JsonObject = buildJsonObject {
        put("xid", xid); put("ix", ix); put("n", n); put("len", len); put("sha", sha); put("d", d)
    }

    companion object {
        private val SHA = Regex("^[0-9a-f]{64}$")
        fun parse(p: JsonObject): Chunk {
            requireOnly(p, setOf("xid", "ix", "n", "len", "sha", "d"), "chunk")
            val c = Chunk(
                xid = p.str("xid")?.takeIf(::isWireUuid) ?: throw WireException("invalid", "chunk.xid"),
                ix = p.int("ix") ?: throw WireException("invalid", "chunk.ix"),
                n = p.int("n") ?: throw WireException("invalid", "chunk.n"),
                len = p.int("len") ?: throw WireException("invalid", "chunk.len"),
                sha = p.str("sha")?.takeIf { SHA.matches(it) } ?: throw WireException("invalid", "chunk.sha"),
                d = p.str("d") ?: throw WireException("invalid", "chunk.d"),
            )
            if (c.n !in 1..64 || c.ix !in 0 until c.n || c.len !in 1..65536) throw WireException("invalid", "chunk bounds")
            return c
        }
    }
}

/** Profile defaults sent in `profile.put` (protocol §6). */
object ProfilePayload {
    fun toJson(w: WatchDefaults, unit: String, restSec: Int): JsonObject = buildJsonObject {
        put("prep", w.prepSec)
        put("snd", w.sound)
        put("vib", w.vibration)
        put("rec", w.record)
        put("tch", w.touch)
        put("ws", JsonPrimitive(w.weightStep))
        put("wsf", JsonPrimitive(w.weightStepFine))
        put("u", unit)
        put("rest", restSec)
        put("tap", w.tapToLog)
    }
}

/**
 * WatchPlan codec plus the receiver-side structural validation the watch applies (protocol §4,
 * §8). The phone runs the same checks on its own output before sending (REQ-SY14).
 */
object WatchPlanCodec {
    private val MODES = setOf("for_time", "amrap", "emom", "tabata", "intervals", "max_effort", "strength_sets", "build_to_max", "manual")
    private val SEG_KINDS = setOf("work", "rest", "open")
    private val SCORE_TYPES = setOf("time", "rounds_reps", "reps", "load", "calories", "distance", "minutes_reps", "completion", "text", "none")

    fun toJson(plan: WatchPlan): JsonObject = WireJson.encodeToJsonElement(WatchPlan.serializer(), plan).jsonObject

    fun parse(p: JsonObject): WatchPlan {
        WireValues.requireNoNulls(p)
        if (p.int("sv") != 1) throw WireException("version", "Unsupported plan schema version ${p["sv"]}")
        val plan = try {
            WireJson.decodeFromJsonElement(WatchPlan.serializer(), p)
        } catch (e: Exception) {
            throw WireException("invalid", "Plan does not match the schema: ${e.message?.lineSequence()?.firstOrNull()}")
        }
        validate(plan)
        return plan
    }

    /** Structural limits; throws [WireException] with the ack reason. */
    fun validate(plan: WatchPlan) {
        fun bad(msg: String): Nothing = throw WireException("invalid", msg)
        fun line(s: String, what: String) { if (s.length > WatchLimits.LINE) bad("$what longer than ${WatchLimits.LINE}: '$s'") }
        if (!isWireUuid(plan.id) || !isWireUuid(plan.wid)) bad("plan ids")
        if (plan.rev < 1 || plan.wr < 1) bad("plan revisions")
        line(plan.ti, "ti")
        if (plan.u !in setOf("kg", "lb")) bad("plan.u")
        if (plan.p.isEmpty() || plan.p.size > WatchLimits.PARTS) bad("plan has ${plan.p.size} parts")
        plan.p.forEach { part ->
            if (!isWireUuid(part.id)) bad("part.id")
            if (part.l.isEmpty() || part.l.length > 3) bad("part.l")
            line(part.n, "part.n")
            if (part.mode !in MODES) bad("mode ${part.mode}")
            if (part.ln.size > WatchLimits.LINES) bad("more than ${WatchLimits.LINES} lines")
            part.ln.forEach { line(it, "ln") }
            part.mv?.forEach { line(it, "mv") }
            if (part.fb && (part.mode != "manual" || part.sc.man != true)) bad("fallback part must be manual with a manual score")
            val strength = part.mode == "strength_sets" || part.mode == "build_to_max"
            if (strength && part.sets.isNullOrEmpty()) bad("${part.mode} needs sets")
            if (!strength && part.seg.isNullOrEmpty()) bad("${part.mode} needs seg")
            if ((part.sets?.size ?: 0) > WatchLimits.SETS) bad("too many sets")
            part.att?.let { if (it !in 1..30) bad("att") }
            part.seg?.forEach { s ->
                if (s.k !in SEG_KINDS) bad("segment kind ${s.k}")
                if (s.lb.length > WatchLimits.SEGMENT_LABEL) bad("segment label")
                if (s.k != "open" && (s.d == null || s.d < 1)) bad("${s.k} segment needs d")
                if (s.k != "open" && s.cap != null) bad("cap only on open segments")
                if (s.k == "open" && s.d != null) bad("open segment has d")
            }
            part.sets?.forEach { s ->
                if (!isWireUuid(s.id)) bad("set.id")
                if (s.nm.length > WatchLimits.SET_NAME) bad("set.nm")
                if (s.rx.length > WatchLimits.REP_TEXT) bad("set.rx")
                if (s.w != null && s.bw == true) bad("set has both w and bw")
                s.nt?.let { line(it, "set.nt") }
            }
            if (part.sc.ty !in SCORE_TYPES) bad("score type ${part.sc.ty}")
            part.rl?.forEach { if (it.length > WatchLimits.ROUND_LABEL) bad("rl") }
            part.lvs?.let { if (it.size > 6) bad("lvs") }
        }
    }
}

internal fun arr(p: JsonObject, k: String): JsonArray =
    p[k] as? JsonArray ?: throw WireException("invalid", "$k must be an array")

internal fun requireOnly(p: JsonObject, allowed: Set<String>, what: String) {
    val extra = p.keys - allowed
    if (extra.isNotEmpty()) throw WireException("invalid", "Unknown $what keys $extra")
}

internal fun JsonElement.asObject(what: String): JsonObject =
    this as? JsonObject ?: throw WireException("invalid", "$what must be an object")
