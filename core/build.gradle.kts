import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
}

// Build identity is generated from the single version property in
// gradle.properties, so no target can drift from another. BuildInfo (hand
// written, see Version.kt) re-exports these constants and owns the comparison
// logic, keeping BuildInfo.NAME/VERSION/... as the public surface.
val buildConstantsDir = layout.buildDirectory.dir("generated/build-constants/kotlin")

val generateBuildConstants by tasks.registering {
    val outputDir = buildConstantsDir
    val productName = rootProject.name
    val productVersion = version.toString()
    val apiVersion = (findProperty("barezen.apiVersion") as String?) ?: "1"
    val repositoryUrl = (findProperty("barezen.repositoryUrl") as String?)
        ?: "https://github.com/linanwanttodo/BareZen-Drive"
    inputs.property("productName", productName)
    inputs.property("productVersion", productVersion)
    inputs.property("apiVersion", apiVersion)
    inputs.property("repositoryUrl", repositoryUrl)
    outputs.dir(outputDir)
    doLast {
        val dir = outputDir.get().asFile.resolve("com/linan/barezen_drive/core")
        dir.mkdirs()
        dir.resolve("BuildConstants.kt").writeText(
            """
            |// Generated from gradle.properties by :core:generateBuildConstants.
            |// Do not edit; change the version property instead.
            |package com.linan.barezen_drive.core
            |
            |object BuildConstants {
            |    const val NAME = "$productName"
            |    const val VERSION = "$productVersion"
            |    const val API_VERSION = $apiVersion
            |    const val REPOSITORY_URL = "$repositoryUrl"
            |}
            |
            """.trimMargin(),
        )
    }
}

kotlin {
    jvm()

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    android {
        namespace = "com.linan.barezen_drive.core"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
        androidResources {
            enable = true
        }
        withHostTest {
            isIncludeAndroidResources = true
        }
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(generateBuildConstants)
            dependencies {
                implementation(libs.kotlinx.serializationJson)
            }
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

// The srcDir wiring above carries the task dependency, but be explicit so the
// generated constants always exist before any Kotlin compilation runs.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
    dependsOn(generateBuildConstants)
}
