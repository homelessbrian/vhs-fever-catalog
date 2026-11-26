plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun envOr(name: String, fallback: String) = System.getenv(name)?.takeIf { it.isNotBlank() } ?: fallback

android {
    namespace = "org.vhsfever.review"
    compileSdk = 35
    defaultConfig {
        applicationId = "org.vhsfever.review"
        minSdk = 26
        targetSdk = 35
        versionCode = envOr("GITHUB_RUN_NUMBER", "1").toInt()
        versionName = "0.1." + envOr("GITHUB_RUN_NUMBER", "1")
        buildConfigField("String", "TMDB_TOKEN", "\"" + envOr("TMDB_API_READ_ACCESS_TOKEN", "") + "\"")
        buildConfigField("String", "REPO", "\"homelessbrian/vhs-fever-catalog\"")
    }
    signingConfigs {
        create("stable") {
            // A fixed key kept in the repo so every build can update the last one.
            storeFile = file("vhsfever-review.keystore")
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
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
