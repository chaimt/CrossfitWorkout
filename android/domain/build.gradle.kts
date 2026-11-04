plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // Android consumes this module, so emit bytecode the app's D8 accepts.
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        optIn.add("kotlinx.serialization.ExperimentalSerializationApi")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

/** Repo-level shared contracts (catalog, fixtures) live outside android/. */
val sharedDir = rootProject.layout.projectDirectory.dir("../shared")

// The movement catalog and benchmark list are generated into shared/ (REQ-DM12);
// bundle them as classpath resources instead of keeping hand copies.
val syncSharedCatalog by tasks.registering(Sync::class) {
    from(sharedDir) {
        include("movements.json", "benchmarks.json")
    }
    into(layout.buildDirectory.dir("generated/shared-resources"))
}

sourceSets {
    main {
        resources.srcDir(syncSharedCatalog)
    }
}

dependencies {
    api(libs.kotlinx.serialization.json)
    api(libs.okhttp)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    systemProperty("shared.dir", sharedDir.asFile.absolutePath)
    systemProperty("golden.update", System.getProperty("golden.update") ?: "false")
    systemProperty(
        "golden.dir",
        layout.projectDirectory.dir("src/test/resources/fixtures").asFile.absolutePath,
    )
    testLogging {
        events("failed")
        showStandardStreams = false
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// `./gradlew :domain:eval` runs the parser eval harness over shared/fixtures (REQ-AX12).
application {
    mainClass.set("com.wodcapture.domain.eval.EvalCliKt")
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.layout.projectDirectory.asFile
    args = listOf(sharedDir.dir("fixtures").asFile.absolutePath)
}

tasks.register<JavaExec>("eval") {
    group = "verification"
    description = "Runs the parser eval harness over shared/fixtures (needs ANTHROPIC_API_KEY)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.wodcapture.domain.eval.EvalCliKt")
    args = listOf(
        sharedDir.dir("fixtures").asFile.absolutePath,
        layout.projectDirectory.dir("src/test/resources/fixtures").asFile.absolutePath,
    )
}
