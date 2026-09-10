plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.ktor)
}

group = "com.linan.barezen_drive"
// Inherits the single version from gradle.properties (rootProject.version).
version = rootProject.version.toString()
application {
    mainClass = "com.linan.barezen_drive.ApplicationKt"
}

dependencies {
    api(project(":core"))
    // GHSA-c4c3-7fpv-j4q5 (critical): ktor-server-netty 3.5.2 pulls netty-handler
    // 4.2.16.Final; force the patched 4.2.17.Final until a Ktor bump supersedes it.
    constraints {
        implementation("io.netty:netty-handler:4.2.17.Final")
    }
    implementation(libs.logback)
    implementation(libs.ktor.serverCore)
    implementation(libs.ktor.serverNetty)
    implementation(libs.ktor.serverContentNegotiation)
    implementation(libs.ktor.serializationJson)
    implementation(libs.ktor.serverAuth)
    implementation(libs.ktor.serverAuthJwt)
    implementation(libs.ktor.serverStatusPages)
    implementation(libs.ktor.serverCallLogging)
    implementation(libs.ktor.serverPartialContent)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.postgresql)
    implementation(libs.hikari)
    implementation(libs.bcrypt)
    testImplementation(libs.h2)
    testImplementation(libs.ktor.serverTestHost)
    testImplementation(libs.kotlin.testJunit)
}