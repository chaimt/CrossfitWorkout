package com.wodcapture.domain.eval

import com.wodcapture.domain.catalog.MovementCatalog
import com.wodcapture.domain.parser.CloudLlmParser
import com.wodcapture.domain.parser.CloudParserConfig
import com.wodcapture.domain.parser.OkHttpTransport
import java.io.File
import kotlin.system.exitProcess

/**
 * `./gradlew :domain:eval` — runs the cloud parser over `shared/fixtures/<H*>/input.txt` and
 * prints field-level accuracy (REQ-AX12). Args: fixturesDir goldenDir [runs]. Without
 * `ANTHROPIC_API_KEY` it reports what it would run and exits 0, so CI/taskfile stay green.
 * Model and effort come from `EVAL_MODEL` / `EVAL_EFFORT`.
 */
fun main(args: Array<String>) {
    val fixtures = File(args.getOrElse(0) { "../shared/fixtures" })
    val golden = File(args.getOrElse(1) { "../shared/fixtures" })
    val runs = args.getOrNull(2)?.toIntOrNull() ?: 1
    val inputs = fixtures.listFiles().orEmpty().filter { File(it, "input.txt").isFile }.map { it.name }.sorted()
    val key = System.getenv("ANTHROPIC_API_KEY")
    if (key.isNullOrBlank()) {
        System.err.println("ANTHROPIC_API_KEY is not set: skipping cloud parser eval (${inputs.size} fixtures: ${inputs.joinToString()}).")
        return
    }
    val config = CloudParserConfig(
        apiKey = key,
        model = System.getenv("EVAL_MODEL") ?: CloudParserConfig.DEFAULT_MODEL,
        effort = System.getenv("EVAL_EFFORT") ?: "medium",
    )
    val parser = CloudLlmParser(config, MovementCatalog.fromClasspath(), OkHttpTransport())
    val report = EvalHarness.run(parser, fixtures, golden, runs)
    println("Parser eval: model=${config.model} effort=${config.effort} prompt=${com.wodcapture.domain.parser.SystemPrompt.VERSION} runs=$runs")
    print(report.render())
    if (report.fixtures.isEmpty()) exitProcess(1)
}
