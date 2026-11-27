plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun envOr(name: String, fallback: String) = System.getenv(name)?.takeIf { it.isNotBlank() } ?: fallback

android {
    namespace = "org.vhsfever.tv"
    compileSdk = 35
    defaultConfig {
        applicationId = "org.vhsfever.tv"
        minSdk = 23
        targetSdk = 35
        versionCode = envOr("GITHUB_RUN_NUMBER", "1").toInt()
        versionName = "0.1." + envOr("GITHUB_RUN_NUMBER", "1")
        buildConfigField("String", "TMDB_TOKEN", "\"" + envOr("TMDB_API_READ_ACCESS_TOKEN", "") + "\"")
        buildConfigField("String", "REPO", "\"homelessbrian/vhs-fever-catalog\"")
        // Read-only key for the private box-art repository (VHS sleeve scans from videotapes.io).
        buildConfigField("String", "BOXART_TOKEN", "\"" + envOr("BOXART_READ_TOKEN", "") + "\"")
        buildConfigField("String", "BOXART_REPO", "\"homelessbrian/vhs-fever-boxart\"")
    }
    signingConfigs {
        create("stable") {
            // A fixed key kept in the repo so every build installs over the last one.
            storeFile = file("vhsfever-tv.keystore")
            storePassword = "vhsfever"
            keyAlias = "vhsfever"
            keyPassword = "vhsfever"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("stable") }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("stable")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Lets a machine that can't reach Maven at test time point Robolectric at a downloaded Android jar.
            System.getenv("ROBOLECTRIC_DEPS_DIR")?.let { dir ->
                it.systemProperty("robolectric.offline", "true")
                it.systemProperty("robolectric.dependency.dir", dir)
            }
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.tv:tv-material:1.0.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    // Screenshots of the screens, rendered on the build machine (see ScreensTest).
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.test.ext:junit:1.2.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// Show why a test failed right in the build log.
tasks.withType<Test>().configureEach {
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = false
    }
}
