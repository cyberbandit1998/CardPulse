plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// CI sets this when the repository has a signing key (see the README, "Updating in place"). Builds signed with the
// same key install over each other; without it (local builds, forks) the usual throwaway debug key is used.
val fixedKeystore: String? = System.getenv("CARDPULSE_KEYSTORE_PATH")

// ./gradlew :app:testDebugUnitTest -Pscreenshots draws every screen to a PNG (src/screenshots). That needs an Android
// runtime on the JVM, so none of it is set up unless asked for: normal builds, tests and the APK stay exactly as they were.
val screenshots = project.hasProperty("screenshots")

android {
    namespace = "app.cardpulse.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.cardpulse.android"
        minSdk = 26
        targetSdk = 36
        // CI numbers its builds 1, 2, 3...; Android only accepts an update whose versionCode isn't lower.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "0.2.0"
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
        release {
            // R8 drops the code and resources nobody uses. Most of a debug APK is libraries the app barely touches:
            // the whole Material icon set for the sixteen icons it shows, for one. It also turns off debugging and
            // lets Compose's own start-up profiles apply, which is most of why a release build starts and scrolls faster.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // The fixed key when CI has one, so the build installs over earlier ones; otherwise the usual debug key,
            // which is enough to install a build made locally or in a fork.
            signingConfig = signingConfigs.getByName(if (fixedKeystore != null) "fixed" else "debug")
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

    if (screenshots) {
        testOptions {
            // The screenshot tests run the real screens on the JVM (Robolectric), which needs the app's resources.
            unitTests.isIncludeAndroidResources = true
        }
        sourceSets.getByName("test").java.srcDir("src/screenshots/java")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// CI shows the build log rather than the HTML report, so print why a test failed right there.
tasks.withType<Test>().configureEach {
    if (screenshots) {
        // Only the screen pictures and the tests that press the screens: the ordinary ones already ran in the step before.
        filter.includeTestsMatching("*ScreenshotTest")
        filter.includeTestsMatching("*BehaviourTest")
        systemProperty("screens.dir", layout.buildDirectory.dir("screens").get().asFile.path)
        maxHeapSize = "2g"
    }
    testLogging {
        // The run that draws the screens also names the tests that passed, so the log shows the pressing tests really ran.
        if (screenshots) events("passed", "failed") else events("failed")
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

    if (screenshots) {
        // The manifest piece registers a test activity; it must never be part of the APK people install.
        debugImplementation("androidx.compose.ui:ui-test-manifest")
        testImplementation("androidx.compose.ui:ui-test-junit4")
        testImplementation("androidx.test.ext:junit:1.2.1")
        testImplementation("androidx.test:core:1.6.1")
        testImplementation("org.robolectric:robolectric:4.14.1")
    }
}
