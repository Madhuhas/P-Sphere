import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure-Kotlin core shared by the Android app (mobile/) and the Windows app (desktop-app/).
// No Android or desktop APIs here, and no third-party runtime dependencies.
plugins {
    kotlin("jvm") version "2.4.21"
    `java-library`
}

group = "com.psphere"
version = "3.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

// Manual-testing device; see src/test/kotlin/com/psphere/core/Harness.kt. Arguments are separated by ','.
tasks.register<JavaExec>("harness") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.psphere.core.HarnessKt")
    args = (project.findProperty("args") as String?)?.split(',') ?: emptyList()
}

tasks.test {
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
