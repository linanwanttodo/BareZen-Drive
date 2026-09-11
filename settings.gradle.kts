rootProject.name = "BareZen-Drive"

pluginManagement {
    repositories {
        // Authoritative sources first: the aliyun mirrors below have shown lag
        // and 502s on freshly published artifacts (Compose wasm klibs), which
        // broke CI. They remain as latency fallbacks for local builds.
        google {
            mavenContent { includeGroupAndSubgroups("androidx"); includeGroupAndSubgroups("com.android"); includeGroupAndSubgroups("com.google") }
        }
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.aliyun.com/repository/google") {
            content { includeGroupAndSubgroups("androidx"); includeGroupAndSubgroups("com.android"); includeGroupAndSubgroups("com.google") }
        }
        maven("https://maven.aliyun.com/repository/public")
    }
}

dependencyResolutionManagement {
    repositories {
        // Authoritative sources first (see pluginManagement above): CI must
        // never depend on mirror freshness for new releases.
        google {
            mavenContent { includeGroupAndSubgroups("androidx"); includeGroupAndSubgroups("com.android"); includeGroupAndSubgroups("com.google") }
        }
        mavenCentral()
        maven("https://maven.aliyun.com/repository/google") {
            content { includeGroupAndSubgroups("androidx"); includeGroupAndSubgroups("com.android"); includeGroupAndSubgroups("com.google") }
        }
        maven("https://maven.aliyun.com/repository/public")
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
