plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.po4yka.frameport.nativebridge"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
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

val rustWorkspaceDir = layout.projectDirectory.dir("../../rust/fuji-rs")

tasks.register<Exec>("cargoTestRust") {
    description = "Runs Rust unit tests for the Frameport Fuji SDK workspace."
    group = "verification"
    workingDir = rustWorkspaceDir.asFile
    commandLine("cargo", "test", "--workspace")
}

tasks.register<Exec>("cargoBuildRustArm64") {
    description = "Builds the Rust JNI library for Android arm64-v8a with cargo-ndk. This task is explicit and is not wired into assemble."
    group = "build"
    workingDir = rustWorkspaceDir.asFile
    commandLine(
        "cargo",
        "ndk",
        "-t",
        "arm64-v8a",
        "-o",
        layout.projectDirectory.dir("src/main/jniLibs").asFile.absolutePath,
        "build",
        "-p",
        "fuji-ffi",
    )
}

tasks.register<Exec>("cargoBuildRustX86_64") {
    description = "Builds the Rust JNI library for Android x86_64 with cargo-ndk for emulator debugging. This task is explicit and is not wired into assemble."
    group = "build"
    workingDir = rustWorkspaceDir.asFile
    commandLine(
        "cargo",
        "ndk",
        "-t",
        "x86_64",
        "-o",
        layout.projectDirectory.dir("src/main/jniLibs").asFile.absolutePath,
        "build",
        "-p",
        "fuji-ffi",
    )
}

dependencies {
    implementation(projects.core.model)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}
