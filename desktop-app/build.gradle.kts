import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The Windows app: Compose for Desktop around the shared core in ../shared.
plugins {
    kotlin("jvm") version "2.4.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21"
    id("org.jetbrains.compose") version "1.12.2"
}

group = "com.psphere"
version = "2.3.0"

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
    implementation("org.jetbrains.compose.material3:material3:1.9.0")
    // Pinned upstream at 1.7.3 (no newer release); still works with Compose 1.12.
    implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
    // Finding linked devices on the network (mDNS), like Android's NSD.
    implementation("org.jmdns:jmdns:3.6.5")
    implementation("org.slf4j:slf4j-nop:2.0.20")
    // The QR code shown on Link a device.
    implementation("com.google.zxing:core:3.5.4")
    // When a photo was taken, and which way up it is.
    implementation("com.drewnoakes:metadata-extractor:2.22.0")

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

compose.desktop {
    application {
        mainClass = "com.psphere.desktop.MainKt"
        // For testing: gradlew run -PdataDir=<folder> keeps the real %LOCALAPPDATA%\PSphere untouched.
        (project.findProperty("dataDir") as String?)?.let { jvmArgs("-Dpsphere.dataDir=$it") }
        nativeDistributions {
            // Each format is built on its own system: MSI on Windows, DEB/RPM on Linux, DMG on macOS.
            targetFormats(TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.Dmg)
            packageName = "PSphere"
            packageVersion = "2.3.0"
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
            linux {
                iconFile.set(project.file("icons/psphere.png"))
                packageName = "psphere"
                menuGroup = "Utility"
                appCategory = "Utility"
                shortcut = true
            }
            macOS {
                iconFile.set(project.file("icons/psphere.icns"))
                bundleID = "com.psphere.desktop"
                appCategory = "public.app-category.utilities"
                infoPlist {
                    // macOS asks before an app may find devices on the local network.
                    extraKeysRawXml = """
                        <key>NSLocalNetworkUsageDescription</key>
                        <string>PSphere finds your other devices on this Wi-Fi and backs up with them.</string>
                        <key>NSBonjourServices</key>
                        <array><string>_psphere._tcp</string></array>
                    """
                }
            }
        }
    }
}

tasks.test {
    useJUnit()
}
