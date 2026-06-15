import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "dev.po4yka.frameport"
    compileSdk =
        libs.versions.compileSdk
            .get()
            .toInt()

    defaultConfig {
        applicationId = "dev.po4yka.frameport"
        minSdk =
            libs.versions.minSdk
                .get()
                .toInt()
        targetSdk =
            libs.versions.targetSdk
                .get()
                .toInt()
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing: reads credentials from keystore.properties (gitignored) or environment
    // variables. Falls back to the debug keystore for local development with a build warning.
    // keystore.properties format (place at repo root, never commit):
    //   storeFile=/absolute/path/to/release.jks
    //   storePassword=...
    //   keyAlias=...
    //   keyPassword=...
    // Environment variable equivalents (for CI):
    //   FRAMEPORT_STORE_FILE, FRAMEPORT_STORE_PASSWORD,
    //   FRAMEPORT_KEY_ALIAS, FRAMEPORT_KEY_PASSWORD
    val keystorePropertiesFile = rootProject.file("keystore.properties")
    val keystoreProps =
        Properties().also { props ->
            if (keystorePropertiesFile.exists()) props.load(keystorePropertiesFile.inputStream())
        }

    fun keystoreValue(
        propKey: String,
        envKey: String,
    ): String? =
        (keystoreProps[propKey] as? String)?.takeIf { it.isNotBlank() }
            ?: System.getenv(envKey)?.takeIf { it.isNotBlank() }

    val releaseStoreFile = keystoreValue("storeFile", "FRAMEPORT_STORE_FILE")
    val releaseStorePassword = keystoreValue("storePassword", "FRAMEPORT_STORE_PASSWORD")
    val releaseKeyAlias = keystoreValue("keyAlias", "FRAMEPORT_KEY_ALIAS")
    val releaseKeyPassword = keystoreValue("keyPassword", "FRAMEPORT_KEY_PASSWORD")
    val hasReleaseKeystore =
        listOf(
            releaseStoreFile,
            releaseStorePassword,
            releaseKeyAlias,
            releaseKeyPassword,
        ).all { !it.isNullOrBlank() }

    signingConfigs {
        getByName("debug") {
            // Uses the default debug keystore (~/.android/debug.keystore); no overrides needed.
        }
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            // R8 full mode is enabled globally via android.enableR8.fullMode=true in gradle.properties.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig =
                if (hasReleaseKeystore) {
                    signingConfigs.getByName("release")
                } else {
                    logger.warn(
                        "WARNING: release build is signed with the debug keystore. " +
                            "Provide keystore.properties or FRAMEPORT_STORE_FILE env vars " +
                            "before submitting to the Play Store.",
                    )
                    signingConfigs.getByName("debug")
                }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(projects.feature.onboarding)
    implementation(projects.feature.connection)
    implementation(projects.feature.gallery)
    implementation(projects.feature.`import`)
    implementation(projects.feature.remote)
    implementation(projects.feature.liveview)
    implementation(projects.feature.settings)
    implementation(projects.feature.diagnostics)
    implementation(projects.camera.api)
    implementation(projects.camera.data)
    implementation(projects.camera.bluetooth)
    implementation(projects.camera.wifi)
    implementation(projects.camera.usb)
    implementation(projects.camera.media)
    implementation(projects.camera.diagnostics)
    implementation(projects.native.fujiRustAndroid)
    implementation(projects.core.common)
    implementation(projects.core.model)
    implementation(projects.core.designsystem)

    implementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.hilt.android)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.timber)
    implementation(libs.coil.compose)

    // M18: profileinstaller enables the seed baseline-prof.txt to be installed at APK install
    // time, giving ART a warm start on first launch without needing a macrobenchmark run.
    // M18: macrobenchmark/baseline-profile auto-generation deferred — androidx.baselineprofile /
    // com.android.test module not wired against AGP 9.2.1 / compileSdk 37 in this milestone;
    // seed baseline-prof.txt bundled via profileinstaller as a best-effort substitute.
    implementation(libs.androidx.profileinstaller)

    ksp(libs.androidx.hilt.compiler)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
