plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "dev.po4yka.frameport.camera.data"
    compileSdk =
        libs.versions.compileSdk
            .get()
            .toInt()

    defaultConfig {
        minSdk =
            libs.versions.minSdk
                .get()
                .toInt()
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
}

dependencies {
    implementation(projects.camera.api)
    implementation(projects.camera.domain)
    implementation(projects.camera.bluetooth)
    implementation(projects.camera.wifi)
    implementation(projects.camera.usb)
    implementation(projects.camera.media)
    implementation(projects.camera.diagnostics)
    implementation(projects.native.fujiRustAndroid)
    implementation(projects.core.common)
    implementation(projects.core.model)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.hilt.android)
    ksp(libs.androidx.hilt.compiler)
}
