plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.github.fotlab.core.data"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Shared Room infrastructure only — this module holds no entity of any feature.
    api(libs.androidx.room.runtime)
    api(libs.androidx.room.ktx)

    implementation(libs.androidx.core.ktx)
}
