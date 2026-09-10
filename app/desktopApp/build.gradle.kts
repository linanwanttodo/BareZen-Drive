import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(project(":app:shared"))

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)

    implementation(libs.compose.uiToolingPreview)
}

compose.desktop {
    application {
        mainClass = "com.linan.barezen_drive.MainKt"

        nativeDistributions {
            // Linux produces Deb and Rpm; Dmg/Msi only build on their own OS,
            // which is why the release pipeline builds desktop per-runner.
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.Dmg, TargetFormat.Msi)
            packageName = "com.linan.barezen_drive"
            // Single source: gradle.properties version.
            packageVersion = rootProject.version.toString()
        }
    }
}