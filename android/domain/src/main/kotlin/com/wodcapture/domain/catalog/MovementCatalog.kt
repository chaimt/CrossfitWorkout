package com.wodcapture.domain.catalog

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A value + unit pair as used by the catalog's standard loads/heights (`{"v":95,"u":"lb"}`). */
@Serializable
data class CatalogQuantity(val v: Double, val u: String)

/** Standard M/F prescription. */
@Serializable
data class CatalogSplit(val m: CatalogQuantity? = null, val f: CatalogQuantity? = null)

/** One movement of `shared/movements.json` (REQ-DM12, REQ-DM20). */
@Serializable
data class Movement(
    val id: String,
    val name: String,
    val aliases: List<String> = emptyList(),
    val ambiguousAliases: List<String> = emptyList(),
    val category: String,
    val measure: String = "reps",
    val measures: List<String> = emptyList(),
    /** `Y`, `N` or `optional`. */
    val loadable: String = "N",
    val implement: String = "none",
    val side: String? = null,
    val rx: CatalogSplit? = null,
    val target: CatalogSplit? = null,
    val height: CatalogSplit? = null,
    val notes: String? = null,
)

@Serializable
private data class CatalogFile(val schemaVersion: Int, val kind: String, val items: List<Movement>)

/**
 * Movement catalog lookup. Unknown movements are allowed as free text and never block a
 * workout (REQ-DM12); lookups therefore return `null` rather than failing.
 */
class MovementCatalog(val movements: List<Movement>) {
    private val byId = movements.associateBy { it.id }

    /** Unambiguous aliases and names, lower-cased, mapped to the movement. */
    private val byAlias: Map<String, Movement> = buildMap {
        for (m in movements) {
            put(m.name.lowercase(), m)
            for (a in m.aliases) if (a !in m.ambiguousAliases) putIfAbsent(a.lowercase(), m)
        }
    }

    fun byId(id: String?): Movement? = id?.let { byId[it] }

    /** Resolves a board token through names and unambiguous aliases only (REQ-AX15). */
    fun lookup(token: String): Movement? = byAlias[token.trim().lowercase()]

    /**
     * Shortest unambiguous alias that is shorter than the name; used when a display line would
     * exceed the watch width (REQ-SY06 explicit shortening shown in preview).
     */
    fun shortName(id: String?): String? {
        val m = byId(id) ?: return null
        return m.aliases.filter { it !in m.ambiguousAliases && it.length < m.name.length }
            .minByOrNull { it.length }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Parses the versioned wrapper `{"schemaVersion":1,"kind":"movements","items":[…]}`. */
        fun parse(text: String): MovementCatalog {
            val file = json.decodeFromString(CatalogFile.serializer(), text)
            require(file.schemaVersion == 1) { "Unsupported movements.json schemaVersion ${file.schemaVersion}" }
            require(file.kind == "movements") { "Not a movement catalog: kind=${file.kind}" }
            return MovementCatalog(file.items)
        }

        /** Loads the catalog bundled from `shared/movements.json` at build time. */
        fun fromClasspath(resource: String = "/movements.json"): MovementCatalog {
            val text = MovementCatalog::class.java.getResourceAsStream(resource)
                ?.bufferedReader()?.use { it.readText() }
                ?: error("Movement catalog resource $resource missing")
            return parse(text)
        }
    }
}
