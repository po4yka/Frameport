plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "dev.po4yka.frameport.core.storage"
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

ksp {
    // Tell Room where to write schema JSON files. exportSchema = true in @Database requires
    // this arg; without it KSP emits a warning and skips generation.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(projects.core.model)
    // androidx.datastore removed: no DataStore usage exists in this module (verified by grep).
    // If Proto DataStore is added for settings persistence, re-add libs.androidx.datastore here.
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.hilt.android)
    // Jetpack Security (EncryptedSharedPreferences backed by Android Keystore) for
    // EncryptedCredentialStore. Note: androidx.security:security-crypto is in maintenance mode
    // as of 2024; a migration to Tink or Jetpack Datastore with manual AES-GCM encryption
    // via KeyStore may follow in a future milestone.
    implementation(libs.androidx.security.crypto)
    ksp(libs.androidx.room.compiler)
    ksp(libs.androidx.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.room.ktx)
    androidTestImplementation(libs.androidx.room.testing)
}
