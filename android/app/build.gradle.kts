plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The pilot signing key lives in android/keystore. For Play Store / wider release, set these four
// as GitHub secrets (see SETUP.md) so a private key is used instead.
val ksFile = System.getenv("GALLA_KEYSTORE") ?: "../keystore/galla-pilot.jks"
val ksPass = System.getenv("GALLA_KEYSTORE_PASSWORD") ?: "galla-pilot"
val ksAlias = System.getenv("GALLA_KEY_ALIAS") ?: "galla"
val ksKeyPass = System.getenv("GALLA_KEY_PASSWORD") ?: "galla-pilot"
val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "app.galla.captain"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.galla.captain"
        minSdk = 26
        targetSdk = 35
        versionCode = runNumber
        versionName = "0.1.$runNumber"
        buildConfigField("String", "APP_URL", "\"https://littlegasp.com/galla/\"")
    }

    signingConfigs {
        create("galla") {
            storeFile = file(ksFile)
            storePassword = ksPass
            keyAlias = ksAlias
            keyPassword = ksKeyPass
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("galla")
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("galla")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    val camerax = "1.4.1"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-video:$camerax")
}
