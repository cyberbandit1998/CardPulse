plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// CI sets this when the repository has a signing key (see the README, "Updating in place"). Builds signed with the
// same key install over each other; without it (local builds, forks) the usual throwaway debug key is used.
val fixedKeystore: String? = System.getenv("CARDPULSE_KEYSTORE_PATH")

android {
    namespace = "io.github.cyberbandit1998.cardpulse"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.cyberbandit1998.cardpulse"
        minSdk = 26
        targetSdk = 36
        // CI numbers its builds 1, 2, 3...; Android only accepts an update whose versionCode isn't lower.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (fixedKeystore != null) {
            create("fixed") {
                storeFile = file(fixedKeystore)
                storePassword = System.getenv("CARDPULSE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("CARDPULSE_KEY_ALIAS")
                // A PKCS12 keystore (what keytool makes by default) uses one password for the store and the key.
                keyPassword = System.getenv("CARDPULSE_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            if (fixedKeystore != null) signingConfig = signingConfigs.getByName("fixed")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// CI shows the build log rather than the HTML report, so print why a test failed right there.
tasks.withType<Test>().configureEach {
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showCauses = true
        showStackTraces = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-compose:1.12.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.datastore:datastore-preferences:1.1.7")

    implementation("androidx.camera:camera-core:1.6.1")
    implementation("androidx.camera:camera-camera2:1.6.1")
    implementation("androidx.camera:camera-lifecycle:1.6.1")
    implementation("androidx.camera:camera-view:1.6.1")

    implementation("com.squareup.retrofit2:retrofit:3.0.0")
    implementation("com.squareup.retrofit2:converter-kotlinx-serialization:3.0.0")
    implementation("com.squareup.okhttp3:okhttp:5.1.0")
    implementation("com.squareup.okhttp3:logging-interceptor:5.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.1.0")
}
