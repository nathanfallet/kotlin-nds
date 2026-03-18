plugins {
    kotlin("multiplatform") version "2.3.0"
    id("com.google.devtools.ksp") version "2.3.4"
    id("org.jetbrains.kotlinx.kover") version "0.8.3"
    id("org.jetbrains.dokka") version "2.0.0"
    id("com.vanniktech.maven.publish") version "0.30.0"
}

group = "me.nathanfallet.nds"
version = "1.2.0"

repositories {
    mavenCentral()
}

mavenPublishing {
    publishToMavenCentral(com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()
    pom {
        name.set("kotlin-nds")
        description.set("Kotlin Multiplatform utilities to work with .nds files")
        url.set("https://github.com/nathanfallet/kotlin-nds")
        licenses {
            license {
                name.set("Apache 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("nathanfallet")
                name.set("Nathan Fallet")
                email.set("contact@nathanfallet.me")
                url.set("https://nathanfallet.me")
            }
        }
        scm {
            url.set("https://github.com/nathanfallet/kotlin-nds.git")
        }
    }
}

kotlin {
    // Tiers are in accordance with <https://kotlinlang.org/docs/native-target-support.html>
    // Tier 1
    macosX64()
    macosArm64()
    iosSimulatorArm64()
    iosX64()

    // Tier 2
    linuxX64()
    linuxArm64()
    watchosSimulatorArm64()
    watchosX64()
    watchosArm32()
    watchosArm64()
    tvosSimulatorArm64()
    tvosX64()
    tvosArm64()
    iosArm64()

    // Tier 3
    mingwX64()
    watchosDeviceArm64()

    // jvm & js
    jvmToolchain(21)
    jvm {
        testRuns.named("test") {
            executionTask.configure {
                useJUnitPlatform()
            }
        }
    }
    js {
        generateTypeScriptDefinitions()
        binaries.library()
        nodejs()
        browser()
    }

    applyDefaultHierarchyTemplate()
    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
