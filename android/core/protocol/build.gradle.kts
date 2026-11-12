// Pure Kotlin/JVM: no Android dependencies, so it is fast to test and mirrors mac PBProtocol exactly.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(17) }

dependencies {
    api(libs.kotlinx.serialization.json)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.jupiter.params)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // Shared conformance fixtures generated from protocol/PROTOCOL.md.
    val fixtures = rootProject.layout.projectDirectory.dir("../protocol/fixtures").asFile
    systemProperty("pb.fixtures", fixtures.absolutePath)
    inputs.dir(fixtures)
}
