@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import java.util.Locale

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

version = "1.0.1"

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
                implementation(libs.kotlinxSerialization)
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

createArchiveTask("linuxArm64")
createArchiveTask("linuxX64")
createArchiveTask("macosArm64")
createArchiveTask("macosX64")
createArchiveTask("mingwX64", "exe")

fun createArchiveTask(target: String, extension: String = "kexe"): TaskProvider<Zip> {
    val buildType = "release"
    val execName = project.name
    val targetBinaryPath = layout.buildDirectory.file("bin/$target/${buildType}Executable/$execName.$extension")

    return tasks.register<Zip>("package${target.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }}") {
        group = "distribution"
        description = "Packages $target binary into archive"
        archiveBaseName.set("$execName-${project.version}-$target")
        archiveVersion.set("")
        destinationDirectory.set(layout.buildDirectory.dir("dist"))

        val dirName = "$execName-${project.version}-$target"
        into(dirName) {
            from(targetBinaryPath) {
                fileMode = 0b111101101 // 755
                rename { execName.removeSuffix(".kexe") }   // strip .kexe if needed
            }
            from("LICENSE")
            from("README.md")
        }
    }
}
