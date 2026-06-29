import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.doormonitor"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.doormonitor"
        minSdk = 34          // Android 14
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0"

        // Default local HTTP API port. Overridable in Settings at runtime.
        buildConfigField("int", "DEFAULT_HTTP_PORT", "2323")

        // libVLC ships native libraries for every ABI, which bloats the APK to ~200 MB.
        // Restrict to the ABIs we actually deploy on. The Lenovo Tab M11 is arm64-v8a;
        // armeabi-v7a is kept for broader 32-bit tablet compatibility. Add x86_64 if you
        // need to run on an emulator.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        // A release signing config is expected to be supplied via a keystore.properties
        // file (see docs/BUILD.md). Falls back to debug signing if absent so the project
        // still assembles a release variant locally.
        create("release") {
            val props = Properties()
            val propsFile = rootProject.file("keystore.properties")
            if (propsFile.exists()) {
                props.load(propsFile.inputStream())
                storeFile = file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            // WebView remote debugging is gated on BuildConfig.DEBUG in code.
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val keystoreExists = rootProject.file("keystore.properties").exists()
            signingConfig = if (keystoreExists) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/io.netty.versions.properties"
        }
    }
}

dependencies {
    // --- AndroidX core / lifecycle ---
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-service:2.8.6")
    implementation("androidx.lifecycle:lifecycle-process:2.8.6")

    // --- Jetpack Compose (BOM keeps versions aligned) ---
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // --- Persistence ---
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // --- Background work / reliability ---
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // --- Coroutines ---
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // --- JSON ---
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // --- Media3 / ExoPlayer for HLS, MJPEG (via progressive), RTSP fallback ---
    val media3 = "1.4.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-rtsp:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
    implementation("androidx.media3:media3-ui:$media3")

    // --- libVLC for robust RTSP / unusual camera streams ---
    implementation("org.videolan.android:libvlc-all:3.6.0")

    // --- MQTT (HiveMQ client, Mosquitto compatible, MQTT 3 & 5) ---
    implementation("com.hivemq:hivemq-mqtt-client:1.3.3")

    // --- Local HTTP API server ---
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // --- HTTP client for HA REST / webhooks ---
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
