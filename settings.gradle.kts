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
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Echo"
include(":app")

// The on-device models ship as install-time asset packs rather than inside the
// base module: together they are ~300 MB, which alone would put the base module
// at Play's 500 MB ceiling. Install-time keeps them present at first launch, so
// no download UI or "model missing" state is needed.
include(":whisper_models")
include(":embedding_models")
 