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
        // Chaquopy (Python-on-Android) プラグイン配布元
        maven { url = uri("https://chaquo.com/maven") }
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
        // Chaquopy (Python-on-Android) ランタイム/pipホイール配布元
        maven { url = uri("https://chaquo.com/maven") }
        maven {
            url = uri(file("java_packages"))
        }
        flatDir {
            dirs(file("java_packages"))
        }
        flatDir {
            dirs(file("app/libs"))
        }
    }
}

rootProject.name = "nezumi-ai"
include(":app")
