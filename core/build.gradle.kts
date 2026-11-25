import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.2.20"
    application
}

group = "com.rcan88"
version = "0.1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(kotlin("test"))
}

application {
    // Permite rodar o mesmo motor de retransmissão num PC / Raspberry Pi (sem Android).
    mainClass.set("com.rcan88.core.cli.MainKt")
    applicationDefaultJvmArgs = listOf("-Dstdout.encoding=UTF-8", "-Dfile.encoding=UTF-8")
}

tasks.test {
    useJUnitPlatform()
}
