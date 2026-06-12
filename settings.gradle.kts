pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "Frameport"
include(
    ":app",
    ":core:common",
    ":core:model",
    ":core:designsystem",
    ":core:permissions",
    ":core:logging",
    ":core:storage",
    ":core:testing",
    ":native:fuji-rust-android",
    ":camera:api",
    ":camera:domain",
    ":camera:data",
    ":camera:bluetooth",
    ":camera:wifi",
    ":camera:usb",
    ":camera:media",
    ":camera:diagnostics",
    ":feature:onboarding",
    ":feature:connection",
    ":feature:gallery",
    ":feature:import",
    ":feature:remote",
    ":feature:liveview",
    ":feature:settings",
    ":feature:diagnostics",
)
