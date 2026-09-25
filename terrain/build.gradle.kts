plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "pl.mazovia.offroad.terrain"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

// TA-001B: externally generated fixtures / real-data work dir for opt-in JVM tests (never inside the repository).
// Pass with -Pterrain.fixtures.dir=<dir> and -Pterrain.gdata.workDir=<dir>; tests that need them are skipped otherwise.
tasks.withType<Test>().configureEach {
    listOf("terrain.fixtures.dir", "terrain.gdata.workDir", "terrain.gdata.outDir").forEach { key ->
        project.findProperty(key)?.let { systemProperty(key, it.toString()) }
    }
    maxHeapSize = "2g"
}

dependencies {
    implementation(project(":domain"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
