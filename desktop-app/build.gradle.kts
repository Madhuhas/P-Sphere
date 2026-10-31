import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The Windows app: Compose for Desktop around the shared core in ../shared.
plugins {
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21"
    id("org.jetbrains.compose") version "1.7.3"
}

group = "com.psphere"
version = "2.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation("com.psphere:psphere-shared")
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.1")
    // Finding linked devices on the network (mDNS), like Android's NSD.
    implementation("org.jmdns:jmdns:3.5.9")
    implementation("org.slf4j:slf4j-nop:2.0.16")
    // The QR code shown on Link a device.
    implementation("com.google.zxing:core:3.5.3")
    // When a photo was taken, and which way up it is.
    implementation("com.drewnoakes:metadata-extractor:2.19.0")

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

compose.desktop {
    application {
        mainClass = "com.psphere.desktop.MainKt"
        // For testing: gradlew run -PdataDir=<folder> keeps the real %LOCALAPPDATA%\PSphere untouched.
        (project.findProperty("dataDir") as String?)?.let { jvmArgs("-Dpsphere.dataDir=$it") }
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "PSphere"
            packageVersion = "2.0.0"
            description = "Back up folders between your devices over your home Wi-Fi"
            vendor = "Kotini Sai Madhuhas"
            copyright = "Copyright (C) 2026 Kotini Sai Madhuhas. GNU AGPL-3.0."
            licenseFile.set(project.file("../LICENSE"))
            // java.naming: JmDNS. jdk.crypto.ec: the TLS between devices uses EC keys.
            modules("java.naming", "jdk.crypto.ec", "java.management")
            windows {
                iconFile.set(project.file("icons/psphere.ico"))
                menuGroup = "PSphere"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                // Keep this fixed: Windows uses it to recognise upgrades of the same app.
                upgradeUuid = "6f1d8a52-3c47-4e0b-9a2e-7b5c1f0d9e34"
            }
        }
    }
}

tasks.test {
    useJUnit()
}
