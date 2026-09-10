import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

/** major*10000 + minor*100 + patch, derived from the single gradle.properties version. */
fun versionCodeOf(version: String): Int {
    val parts = version.trim().removePrefix("v").split('.', '-', '+')
        .map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
    return parts.getOrElse(0) { 0 } * 10_000 + parts.getOrElse(1) { 0 } * 100 + parts.getOrElse(2) { 0 }
}

// Release signing is opt-in: the CI release job provides a keystore through
// these environment variables (or the matching Gradle properties). When they
// are absent the release build still runs and produces an unsigned APK, so
// forks and local builds are unaffected.
val keystorePath: String? = (System.getenv("ANDROID_KEYSTORE_PATH")
    ?: findProperty("android.keystore.path") as String?)?.takeIf { it.isNotBlank() }
val keystorePassword: String? = (System.getenv("ANDROID_KEYSTORE_PASSWORD")
    ?: findProperty("android.keystore.password") as String?)?.takeIf { it.isNotBlank() }
val keystoreAlias: String? = (System.getenv("ANDROID_KEY_ALIAS")
    ?: findProperty("android.key.alias") as String?)?.takeIf { it.isNotBlank() }
val keyPassword: String? = (System.getenv("ANDROID_KEY_PASSWORD")
    ?: findProperty("android.key.password") as String?)?.takeIf { it.isNotBlank() }
val hasReleaseSigning = keystorePath != null && keystorePassword != null &&
    keystoreAlias != null && keyPassword != null

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}
dependencies {
    implementation(project(":app:shared"))

    implementation(libs.androidx.activity.compose)

    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)
}

android {
    namespace = "com.linan.barezen_drive"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.linan.barezen_drive"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        // Both derive from the single version in gradle.properties so they
        // cannot drift; versionCode is major*10000 + minor*100 + patch.
        versionName = rootProject.version.toString()
        versionCode = versionCodeOf(rootProject.version.toString())
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    if (hasReleaseSigning) {
        signingConfigs {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = keystorePassword
                keyAlias = keystoreAlias
                keyPassword = keyPassword
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}
