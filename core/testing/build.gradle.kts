plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(projects.camera.api)
    implementation(projects.core.model)
    implementation(libs.junit)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.mockk)
    implementation(libs.turbine)
}
