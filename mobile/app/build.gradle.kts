import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.psphere"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.psphere"
        minSdk = 26
        targetSdk = 34
        // 2.0: linked devices, per-folder destinations, encrypted protocol v3 (not compatible with 1.x).
        versionCode = 3
        versionName = "2.0"

        buildConfigField("String", "BASE_URL", "\"http://10.0.2.2:3000\"")
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

    kotlinOptions {
        jvmTarget = "17"
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
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp.logging)
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
