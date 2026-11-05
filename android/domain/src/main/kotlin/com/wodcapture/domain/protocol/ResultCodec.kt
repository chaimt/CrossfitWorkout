package com.wodcapture.domain.protocol

import com.wodcapture.domain.isWireUuid
import com.wodcapture.domain.model.LoadObservation
import com.wodcapture.domain.model.Mass
import com.wodcapture.domain.model.MassUnit
import com.wodcapture.domain.model.DistanceUnit
import com.wodcapture.domain.model.PartScore
import com.wodcapture.domain.model.ProgressEvent
import com.wodcapture.domain.model.ResultSource
import com.wodcapture.domain.model.ResultStatus
import com.wodcapture.domain.model.Score
import com.wodcapture.domain.model.Session
import com.wodcapture.domain.model.SessionEntry
import com.wodcapture.domain.model.SetObservation
import com.wodcapture.domain.model.SetStatus
import com.wodcapture.domain.model.WorkoutResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Wire score `{ty, rv, man?, cs?, s|r,x|n|v,u|i,x|ok|tx}` (protocol §5). */
data class WireScore(
    val ty: String,
    val rv: Int,
    val man: Boolean? = null,
    val cs: Boolean? = null,
    val s: Int? = null,
    val r: Int? = null,
    val x: Int? = null,
    val n: Int? = null,
    val v: Double? = null,
    val u: String? = null,
    val i: Int? = null,
    val ok: Boolean? = null,
    val tx: String? = null,
    /** Pending: not entered on the watch, to be completed on the phone; never read as 0. */
    val pd: Boolean? = null,
) {
    val pending: Boolean get() = pd == true

    /** Domain score (REQ-DM22), or `null` while pending. Manual/corrected flags move to [PartScore]. */
    fun toDomain(): PartScore? {
        if (pending) return null
        val score: Score = when (ty) {
            "time" -> Score.Time(s!!)
            "rounds_reps" -> Score.RoundsReps(r!!, x!!)
            "reps" -> Score.Reps(n!!)
            "calories" -> Score.Calories(n!!)
            "load" -> Score.Load(v!!, massUnit(u!!))
            "distance" -> Score.Distance(v!!, DistanceUnit.entries.first { it.wire == u })
            "minutes_reps" -> Score.MinutesReps(i!!, x!!)
            "completion" -> Score.Completion(ok!!)
            "text" -> Score.Text(tx!!)
            else -> Score.None
        }
        return PartScore(score, manual = man == true, ruleVersion = rv, corrected = cs == true)
    }

    fun toJson(): JsonObject = buildJsonObject {
        put("ty", ty)
        s?.let { put("s", it) }
        r?.let { put("r", it) }
        x?.let { put("x", it) }
        n?.let { put("n", it) }
        v?.let { put("v", num(it)) }
        u?.let { put("u", it) }
        i?.let { put("i", it) }
        ok?.let { put("ok", it) }
        tx?.let { put("tx", it) }
        put("rv", rv)
        man?.let { put("man", it) }
        cs?.let { put("cs", it) }
        pd?.let { put("pd", it) }
    }

    companion object {
        private val REQUIRED = mapOf(
            "time" to listOf("s"), "rounds_reps" to listOf("r", "x"), "reps" to listOf("n"),
            "calories" to listOf("n"), "load" to listOf("v", "u"), "distance" to listOf("v", "u"),
            "minutes_reps" to listOf("i", "x"), "completion" to listOf("ok"), "text" to listOf("tx"),
            "none" to emptyList(),
        )
        private val VALUE_KEYS = setOf("s", "r", "x", "n", "v", "u", "i", "ok", "tx")

        fun parse(o: JsonObject): WireScore {
            requireOnly(o, setOf("ty", "rv", "man", "cs", "s", "r", "x", "n", "v", "u", "i", "ok", "tx", "pd"), "score")
            val ty = o.str("ty") ?: throw WireException("invalid", "score.ty")
            val required = REQUIRED[ty] ?: throw WireException("invalid", "score.ty $ty")
            if (o.bool("pd") == true) {
                // A pending score carries no value: it is completed on the phone, never read as 0.
                if (VALUE_KEYS.any { it in o }) throw WireException("invalid", "pending score must not carry a value")
            } else required.filter { it !in o }.takeIf { it.isNotEmpty() }?.let {
                throw WireException("invalid", "score $ty is missing $it (missing is never 0)")
            }
            val sc = WireScore(
                ty = ty,
                rv = o.int("rv")?.takeIf { it >= 1 } ?: throw WireException("invalid", "score.rv"),
                man = o.bool("man"), cs = o.bool("cs"), s = o.int("s"), r = o.int("r"), x = o.int("x"),
                n = o.int("n"), v = o.num("v"), u = o.str("u"), i = o.int("i"), ok = o.bool("ok"),
                tx = o.str("tx"), pd = o.bool("pd"),
            )
            if (sc.s != null && sc.s !in 0..86399) throw WireException("invalid", "score.s")
            if (sc.n != null && sc.n !in 0..99999) throw WireException("invalid", "score.n")
            if (listOfNotNull(sc.r, sc.x, sc.i).any { it < 0 } || (sc.v ?: 0.0) < 0) throw WireException("invalid", "negative score")
            if (sc.u != null && sc.u !in setOf("kg", "lb", "m", "km", "mi", "ft", "yd")) throw WireException("invalid", "score.u")
            if ((sc.tx?.toByteArray()?.size ?: 0) > 256) throw WireException("invalid", "score.tx > 256 bytes")
            return sc
        }
    }
}

/** Set observation row `[setId, ix, target(-1), actual, load(-1), unit, count, status, ms]`. */
data class WireSetRow(
    val id: String, val ix: Int, val target: Int, val actual: Int, val load: Double,
    val unit: String, val count: Int, val status: String, val ms: Long,
)

/** Result payload of `result.put` (protocol §5), kept close to the wire for lossless re-encoding. */
data class WireResult(
    val sv: Int, val id: String, val rr: Int, val sid: String, val pl: String, val pr: Int,
    val wid: String, val wr: Int, val pt: String, val st: String, val t0: Long,
    val el: Int, val act: Int, val wk: Int, val sc: WireScore, val lv: String? = null,
    val ev: List<ProgressEvent> = emptyList(), val sets: List<WireSetRow> = emptyList(),
    val ld: List<LoadObservation> = emptyList(), val edt: Map<String, Int>? = null,
    val hr: Pair<Int, Int>? = null, val cal: Int? = null, val fit: String, val dev: String,
    val app: String, val nt: String? = null,
    /** Keys present in the source (`ev`, `sets`, `ld`), so empty arrays round-trip. */
    val presentArrays: Set<String> = emptySet(),
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("sv", sv); put("id", id); put("rr", rr); put("sid", sid); put("pl", pl); put("pr", pr)
        put("wid", wid); put("wr", wr); put("pt", pt); put("st", st); put("t0", t0)
        put("el", el); put("act", act); put("wk", wk); put("sc", sc.toJson())
        lv?.let { put("lv", it) }
        if (ev.isNotEmpty() || "ev" in presentArrays) put("ev", JsonArray(ev.map { e ->
            buildJsonArray { add(JsonPrimitive(e.index)); add(JsonPrimitive(e.kind)); add(JsonPrimitive(e.atMs)) }
        }))
        if (sets.isNotEmpty() || "sets" in presentArrays) put("sets", JsonArray(sets.map { s ->
            buildJsonArray {
                add(JsonPrimitive(s.id)); add(JsonPrimitive(s.ix)); add(JsonPrimitive(s.target)); add(JsonPrimitive(s.actual))
                add(num(s.load)); add(JsonPrimitive(s.unit)); add(JsonPrimitive(s.count)); add(JsonPrimitive(s.status)); add(JsonPrimitive(s.ms))
            }
        }))
        if (ld.isNotEmpty() || "ld" in presentArrays) put("ld", JsonArray(ld.map { l ->
            buildJsonArray { add(JsonPrimitive(l.nodeId)); add(num(l.load.value)); add(JsonPrimitive(l.load.unit.wire)); add(JsonPrimitive(l.implementCount)) }
        }))
        edt?.let { m -> put("edt", JsonObject(m.mapValues { JsonPrimitive(it.value) })) }
        hr?.let { put("hr", buildJsonArray { add(JsonPrimitive(it.first)); add(JsonPrimitive(it.second)) }) }
        cal?.let { put("cal", it) }
        put("fit", fit); put("dev", dev); put("app", app)
        nt?.let { put("nt", it) }
    }

    /** Domain result (REQ-DM09). Missing values stay `null`, never 0. */
    fun toDomain(): WorkoutResult = WorkoutResult(
        id = id, sessionId = sid, workoutId = wid, workoutRevision = wr, planId = pl, planRevision = pr,
        partId = pt, resultRevision = rr, startedAt = t0, status = ResultStatus.fromWire(st)!!,
        elapsedSec = el, activeSec = act, workSec = wk, score = sc.toDomain(),
        scorePendingType = if (sc.pending) sc.ty else null, level = lv,
        sets = sets.map { s ->
            SetObservation(
                setId = s.id, index = s.ix, targetReps = s.target.takeIf { it >= 0 }, actualReps = s.actual,
                load = if (s.load < 0 || s.unit.isEmpty()) null else Mass(s.load, massUnit(s.unit)),
                implementCount = s.count, status = SetStatus.fromWire(s.status)!!, atMs = s.ms,
            )
        },
        events = ev, loads = ld, timerEdits = edt.orEmpty(), hrAvg = hr?.first, hrMax = hr?.second,
        calories = cal, fit = fit, source = ResultSource.WATCH, device = dev, appVersion = app, notes = nt,
    )

    companion object {
        private val KEYS = setOf(
            "sv", "id", "rr", "sid", "pl", "pr", "wid", "wr", "pt", "st", "t0", "el", "act", "wk", "sc", "lv",
            "ev", "sets", "ld", "edt", "hr", "cal", "fit", "dev", "app", "nt",
        )
        private val EVENT_KINDS = setOf("rd", "mv", "fin", "cap", "undo")
        private val FIT = setOf("off", "saved", "failed", "unknown")

        fun parse(o: JsonObject): WireResult {
            WireValues.requireNoNulls(o)
            requireOnly(o, KEYS, "result")
            if (o.int("sv") != 1) throw WireException("version", "Unsupported result schema version")
            fun uuid(k: String) = o.str(k)?.takeIf(::isWireUuid) ?: throw WireException("invalid", "result.$k")
            fun pos(k: String) = o.int(k)?.takeIf { it >= 1 } ?: throw WireException("invalid", "result.$k")
            fun nonNeg(k: String) = o.int(k)?.takeIf { it >= 0 } ?: throw WireException("invalid", "result.$k")
            val st = o.str("st")?.takeIf { ResultStatus.fromWire(it) != null } ?: throw WireException("invalid", "result.st")
            val ev = (o["ev"] as? JsonArray)?.map { e ->
                val a = e.row(3, "ev")
                ProgressEvent(a.intAt(0, "ev"), a.strAt(1, "ev").also { if (it !in EVENT_KINDS) throw WireException("invalid", "ev kind $it") }, a.longAt(2, "ev"))
            }.orEmpty()
            if (ev.size > 200) throw WireException("invalid", "more than 200 events")
            val sets = (o["sets"] as? JsonArray)?.map { e ->
                val a = e.row(9, "sets")
                WireSetRow(
                    a.strAt(0, "sets").also { if (!isWireUuid(it)) throw WireException("invalid", "set id") },
                    a.intAt(1, "sets"), a.intAt(2, "sets"), a.intAt(3, "sets"), a.numAt(4, "sets"),
                    a.strAt(5, "sets").also { if (it !in setOf("kg", "lb", "")) throw WireException("invalid", "set unit") },
                    a.intAt(6, "sets"),
                    a.strAt(7, "sets").also { if (SetStatus.fromWire(it) == null) throw WireException("invalid", "set status") },
                    a.longAt(8, "sets"),
                )
            }.orEmpty()
            val ld = (o["ld"] as? JsonArray)?.map { e ->
                val a = e.row(4, "ld")
                LoadObservation(a.strAt(0, "ld"), Mass(a.numAt(1, "ld"), massUnit(a.strAt(2, "ld"))), a.intAt(3, "ld"))
            }.orEmpty()
            val hr = (o["hr"] as? JsonArray)?.let { a -> a.row(2, "hr"); a.intAt(0, "hr") to a.intAt(1, "hr") }
            if (o.containsKey("hr") && hr == null) throw WireException("invalid", "result.hr")
            val edt = (o["edt"] as? JsonObject)?.mapValues { (k, v) -> (v as? JsonPrimitive)?.content?.toIntOrNull() ?: throw WireException("invalid", "edt.$k") }
            val nt = o.str("nt")?.also { if (it.toByteArray().size > 256) throw WireException("invalid", "nt > 256 bytes") }
            return WireResult(
                sv = 1, id = uuid("id"), rr = pos("rr"), sid = uuid("sid"), pl = uuid("pl"), pr = pos("pr"),
                wid = uuid("wid"), wr = pos("wr"), pt = uuid("pt"), st = st,
                t0 = o.long("t0")?.takeIf { it >= 0 } ?: throw WireException("invalid", "result.t0"),
                el = nonNeg("el"), act = nonNeg("act"), wk = nonNeg("wk"),
                sc = WireScore.parse(o["sc"]?.asObject("result.sc") ?: throw WireException("invalid", "result.sc")),
                lv = o.str("lv")?.also { if (it.length > 12) throw WireException("invalid", "result.lv") },
                ev = ev, sets = sets, ld = ld, edt = edt, hr = hr,
                cal = o.int("cal")?.also { if (it < 0) throw WireException("invalid", "result.cal") },
                fit = o.str("fit")?.takeIf { it in FIT } ?: throw WireException("invalid", "result.fit"),
                dev = o.str("dev") ?: throw WireException("invalid", "result.dev"),
                app = o.str("app") ?: throw WireException("invalid", "result.app"),
                nt = nt,
                presentArrays = setOf("ev", "sets", "ld").filter { it in o }.toSet(),
            )
        }
    }
}

/** SessionSummary payload of `session.put` (protocol §5.4, REQ-SY16). */
data class WireSession(
    val sv: Int, val id: String, val rev: Int, val pl: String, val pr: Int,
    val e: List<SessionEntry>, val t0: Long, val t1: Long, val el: Int, val act: Int,
    val hr: Pair<Int, Int>? = null, val cal: Int? = null, val rso: String? = null, val fit: String,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("sv", sv); put("id", id); put("rev", rev); put("pl", pl); put("pr", pr)
        put("e", JsonArray(e.map { en ->
            buildJsonArray { add(JsonPrimitive(en.partId)); add(JsonPrimitive(en.resultId ?: "")); add(JsonPrimitive(en.status.wire)) }
        }))
        put("t0", t0); put("t1", t1); put("el", el); put("act", act)
        hr?.let { put("hr", buildJsonArray { add(JsonPrimitive(it.first)); add(JsonPrimitive(it.second)) }) }
        cal?.let { put("cal", it) }
        rso?.let { put("rso", it) }
        put("fit", fit)
    }

    fun toDomain(): Session = Session(
        id = id, revision = rev, planId = pl, planRevision = pr, entries = e, startedAt = t0, endedAt = t1,
        elapsedSec = el, activeSec = act, hrAvg = hr?.first, hrMax = hr?.second, calories = cal,
        restartOfSessionId = rso, fit = fit,
    )

    companion object {
        fun parse(o: JsonObject): WireSession {
            WireValues.requireNoNulls(o)
            requireOnly(o, setOf("sv", "id", "rev", "pl", "pr", "e", "t0", "t1", "el", "act", "hr", "cal", "rso", "fit"), "session")
            if (o.int("sv") != 1) throw WireException("version", "Unsupported session schema version")
            fun uuid(k: String) = o.str(k)?.takeIf(::isWireUuid) ?: throw WireException("invalid", "session.$k")
            val entries = arr(o, "e").map { el ->
                val a = el.row(3, "e")
                val rid = a.strAt(1, "e")
                if (rid.isNotEmpty() && !isWireUuid(rid)) throw WireException("invalid", "session entry result id")
                SessionEntry(a.strAt(0, "e"), rid.ifEmpty { null }, ResultStatus.fromWire(a.strAt(2, "e")) ?: throw WireException("invalid", "entry status"))
            }
            if (entries.isEmpty() || entries.size > 6) throw WireException("invalid", "session entries")
            val hr = (o["hr"] as? JsonArray)?.let { a -> a.row(2, "hr"); a.intAt(0, "hr") to a.intAt(1, "hr") }
            return WireSession(
                sv = 1, id = uuid("id"), rev = o.int("rev")?.takeIf { it >= 1 } ?: throw WireException("invalid", "session.rev"),
                pl = uuid("pl"), pr = o.int("pr")?.takeIf { it >= 1 } ?: throw WireException("invalid", "session.pr"),
                e = entries,
                t0 = o.long("t0") ?: throw WireException("invalid", "session.t0"),
                t1 = o.long("t1") ?: throw WireException("invalid", "session.t1"),
                el = o.int("el") ?: throw WireException("invalid", "session.el"),
                act = o.int("act") ?: throw WireException("invalid", "session.act"),
                hr = hr, cal = o.int("cal"), rso = o.str("rso")?.also { if (!isWireUuid(it)) throw WireException("invalid", "session.rso") },
                fit = o.str("fit") ?: throw WireException("invalid", "session.fit"),
            )
        }
    }
}

private fun massUnit(u: String): MassUnit = when (u) {
    "kg" -> MassUnit.KG
    "lb" -> MassUnit.LB
    else -> throw WireException("invalid", "mass unit $u")
}

/** Whole doubles encode as integers, matching [com.wodcapture.domain.plan.WireNumberSerializer]. */
internal fun num(d: Double): JsonPrimitive = if (d == Math.rint(d) && kotlin.math.abs(d) < 1e15) JsonPrimitive(d.toLong()) else JsonPrimitive(d)

private fun JsonElement.row(size: Int, what: String): JsonArray {
    val a = this as? JsonArray ?: throw WireException("invalid", "$what row is not an array")
    if (a.size != size) throw WireException("invalid", "$what row must have $size entries")
    return a
}

private fun JsonArray.p(i: Int, what: String): JsonPrimitive = this[i] as? JsonPrimitive ?: throw WireException("invalid", "$what[$i]")
private fun JsonArray.strAt(i: Int, what: String): String = p(i, what).takeIf { it.isString }?.content ?: throw WireException("invalid", "$what[$i] not a string")
private fun JsonArray.longAt(i: Int, what: String): Long = p(i, what).takeIf { !it.isString }?.content?.toLongOrNull() ?: throw WireException("invalid", "$what[$i] not an integer")
private fun JsonArray.intAt(i: Int, what: String): Int = longAt(i, what).toInt()
private fun JsonArray.numAt(i: Int, what: String): Double = p(i, what).takeIf { !it.isString }?.content?.toDoubleOrNull() ?: throw WireException("invalid", "$what[$i] not a number")
