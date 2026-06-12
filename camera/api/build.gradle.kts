plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(projects.core.model)
    api(libs.kotlinx.coroutines.core)
}
