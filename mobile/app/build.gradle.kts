import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.psphere"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.psphere"
        minSdk = 26
        targetSdk = 34
        // 2.0: linked devices, per-folder destinations, encrypted protocol v3 (not compatible with 1.x).
        // 2.1: TLS 1.3 preferred, one-sided-link fix (protocol v4, not compatible with 2.0), check for updates.
        // 2.2: Photos library + Free up space (step 4); unlink purges the device's photos; reinstall & undecodable-file fixes. Still protocol v4 (links with 2.1).
        // 2.3: toolchain upgrade (AGP 9, Kotlin 2.4), Photos hides items no linked device can provide. Still protocol v4.
        versionCode = 6
        versionName = "2.3"
    }

    // Release signing: put storeFile/storePassword/keyAlias/keyPassword in mobile/keystore.properties
    // (never commit it). Without that file the release APK is signed with the local debug key so it
    // can still be installed for testing; such an APK must not be published.
    val keystoreProps = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { f ->
        Properties().apply { f.inputStream().use { load(it) } }
    }
    signingConfigs {
        if (keystoreProps != null) {
            create("release") {
                storeFile     = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias      = keystoreProps.getProperty("keyAlias")
                keyPassword   = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // The release lint check can't find the jar of the included :shared build ("Could not find
    // jar for project :shared"), a known Android Gradle Plugin limit with composite builds.
    lint {
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.coil.compose)
    implementation(libs.zxing.core)
    implementation(libs.gms.code.scanner)
    implementation(libs.androidx.exifinterface)
    implementation("com.psphere:psphere-shared")
    debugImplementation(libs.androidx.ui.tooling)
}
