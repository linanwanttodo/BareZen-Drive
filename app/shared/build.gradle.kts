import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    android {
        namespace = "com.linan.barezen_drive.app.shared"
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
        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    sourceSets {
        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.compose.uiTooling)
        }
        commonMain.dependencies {
            api(project(":core"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.materialIconsCore)
            implementation(libs.compose.uiBackhandler)
            implementation(libs.compose.materialIconsExtended)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.material3.adaptiveNavigationSuite)
            implementation(libs.backdrop)
            implementation(libs.shapes)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.kotlinx.serializationJson)
            implementation(libs.kotlinx.datetime)
            implementation(libs.ktor.serializationJson)
            implementation(libs.ktor.clientCore)
            implementation(libs.ktor.clientAuth)
            implementation(libs.ktor.clientContentNegotiation)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutinesTest)
            implementation(libs.ktor.clientMock)
        }
        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.compose.uiTooling)
            implementation(libs.ktor.clientOkhttp)
            implementation(libs.kotlinx.coroutinesAndroid)
            // NotificationCompat / NotificationManagerCompat for transfer
            // progress notifications.
            implementation(libs.androidx.core.ktx)
            // Periodic background album sync.
            implementation(libs.androidx.work.runtime)
            implementation(libs.androidx.securityCrypto)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.exifinterface)
            implementation(libs.media3.exoplayer)
            implementation(libs.media3.ui)
        }
        wasmJsMain.dependencies {
            implementation(libs.wrappers.browser)
            implementation(libs.ktor.clientJs)
        }
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.uiTooling)
}