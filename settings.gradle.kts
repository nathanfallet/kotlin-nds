rootProject.name = "kotlinds"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include(":nds-all")
include(":nds-rom")
include(":nds-compression")
include(":nds-narc")
include(":nds-sound")
