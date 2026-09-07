// Root build script: declares plugins only, applies nothing.
// The project is a single Gradle module (:app), so only application plugins are
// declared here (`FOTLAB-STRUCT-000001`).
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
