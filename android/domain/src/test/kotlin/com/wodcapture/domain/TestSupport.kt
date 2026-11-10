package com.wodcapture.domain

import com.wodcapture.domain.catalog.MovementCatalog
import com.wodcapture.domain.model.AthleteProfile
import com.wodcapture.domain.model.Mass
import com.wodcapture.domain.model.MassUnit
import com.wodcapture.domain.model.RepMax
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.test.assertEquals

/** Shared helpers for domain tests. */
object TestSupport {
    val catalog: MovementCatalog by lazy { MovementCatalog.fromClasspath() }

    val sharedDir: File = File(System.getProperty("shared.dir") ?: "../../shared")
    val goldenDir: File = File(System.getProperty("golden.dir") ?: "../shared/fixtures")
    private val update = System.getProperty("golden.update") == "true"

    /** Fixture athlete: kg, 80 kg bodyweight, back squat 1RM 125 kg, Appendix D equivalents. */
    val profile = AthleteProfile(
        displayUnit = MassUnit.KG,
        bodyweight = Mass(80.0, MassUnit.KG),
        repMaxes = listOf(RepMax("back_squat", 1, Mass(125.0, MassUnit.KG))),
    )

    fun sharedJson(path: String): JsonObject = Json.parseToJsonElement(File(sharedDir, path).readText()).jsonObject

    /**
     * Compares [actual] with the golden file; writes it when missing or with
     * `-Dgolden.update=true` (review the diff before committing).
     */
    fun assertGolden(relative: String, actual: String) {
        val f = File(goldenDir, relative)
        if (update || !f.exists()) {
            f.parentFile.mkdirs()
            f.writeText(actual)
            return
        }
        assertEquals(f.readText(), actual, "Golden mismatch for $relative (run with -Dgolden.update=true to accept)")
    }
}

/** Deterministic fixture UUID: `f<fixture:2><kind:1>0000-0000-4000-8000-<n:12>`. */
fun fid(fixture: Int, kind: Int, n: Int): String = "f%02d%d0000-0000-4000-8000-%012d".format(fixture, kind, n)
