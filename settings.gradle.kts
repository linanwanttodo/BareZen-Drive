rootProject.name = "BareZen-Drive"

pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/google") {
            content { includeGroupAndSubgroups("androidx"); includeGroupAndSubgroups("com.android"); includeGroupAndSubgroups("com.google") }
        }
        maven("https://maven.aliyun.com/repository/public")
        google {
            mavenContent { includeGroupAndSubgroups("androidx"); includeGroupAndSubgroups("com.android"); includeGroupAndSubgroups("com.google") }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/google") {
            content { includeGroupAndSubgroups("androidx"); includeGroupAndSubgroups("com.android"); includeGroupAndSubgroups("com.google") }
        }
        maven("https://maven.aliyun.com/repository/public") {
            // Aliyun lags on io.github.kyant0 wasm-js klibs; resolve those
            // from Maven Central instead.
            content { excludeGroup("io.github.kyant0") }
        }
        google {
            mavenContent { includeGroupAndSubgroups("androidx"); includeGroupAndSubgroups("com.android"); includeGroupAndSubgroups("com.google") }
        }
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include(":app:androidApp")
include(":app:shared")
include(":app:webApp")
include(":core")
include(":server")
// include(":app:desktopApp") // v0.2 restore desktop target
