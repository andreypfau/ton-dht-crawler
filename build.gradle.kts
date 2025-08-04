@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

repositories {
    mavenLocal()
    mavenCentral()
}

kotlin {
    jvm {
        binaries {
            executable {
                mainClass.set("MainKt")
            }
        }
    }
    macosX64().app()
    macosArm64().app()
    linuxArm64().app()
    linuxX64().app()
    mingwX64().app()

    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.clikt)
                implementation(libs.kotlinxIoCore)
                implementation(libs.tonDht)
            }
        }
    }

    configureSourceSetsLayout()
}

fun KotlinNativeTarget.app() {
    val targetName = this.name
    binaries.executable {
        entryPoint = "main"
    }
}


fun KotlinMultiplatformExtension.configureSourceSetsLayout() {
    sourceSets {
        all {
            if (name.endsWith("Main")) {
                val suffix = if (name.startsWith("common")) "" else "@${name.removeSuffix("Main")}"
                kotlin.srcDir("src$suffix")
                resources.srcDir("resources$suffix")
            }
            if (name.endsWith("Test")) {
                val suffix = if (name.startsWith("common")) "" else "@${name.removeSuffix("Test")}"
                kotlin.srcDir("test$suffix")
                resources.srcDir("testResources$suffix")
            }
        }
    }
}
