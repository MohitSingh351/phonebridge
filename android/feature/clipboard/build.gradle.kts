// Clipboard sync (PROTOCOL.md §8): Mac → phone automatically; phone → Mac via tile, share sheet, notification.
plugins { alias(libs.plugins.android.library) }

android {
    namespace = "dev.phonebridge.clipboard"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.minSdk.get().toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.all { it.useJUnitPlatform() } }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core:transport"))
    implementation(libs.androidx.core.ktx)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
