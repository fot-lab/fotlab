plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Version identity is owned by the repository root files, not by this script.
// See rules/VERSION.md — never edit them from here.
val appVersionName = rootProject.file("VERSION_NAME").readText().trim()
val appVersionCode = rootProject.file("VERSION_CODE").readText().trim().toInt()

// Release signing. keystore.properties is written by CI — see the
// "Get or create release keystore" step in .github/workflows/build_gradle.yaml;
// the keystore itself lives in the dedicated `keystore` repo (branch `keystore`).
// When the file is absent (e.g. a local build) the release build stays unsigned
// instead of failing.
val keystorePropsFile = rootProject.file("keystore.properties")
val hasReleaseKeystore = keystorePropsFile.exists()
val keystoreProps = java.util.Properties().apply {
    if (hasReleaseKeystore) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "io.github.fotlab.fotlab"
    compileSdk = 36

    defaultConfig {
        applicationId = providers.gradleProperty("APP_ID").get()
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(keystoreProps["RELEASE_STORE_FILE"] as String)
                storePassword = keystoreProps["RELEASE_STORE_PASSWORD"] as String
                keyAlias = keystoreProps["RELEASE_KEY_ALIAS"] as String
                keyPassword = keystoreProps["RELEASE_KEY_PASSWORD"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
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
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Shared Room infrastructure (data package). KSP + room-compiler run on the
    // gallery's @Entity/@Database (`FOTLAB-DATABS-000001` R7).
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // User preferences (gallery display mode) kept out of the fs_node Room database.
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)

    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}
