pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "fotlab"

// Single module: all first-party code lives in :app (`FOTLAB-STRUCT-000001`).
// Layers are packages under io.github.fotlab.fotlab (ui/ navigation/ data/),
// not separate Gradle modules. Adding a destination adds a package, not a module.
include(":app")
