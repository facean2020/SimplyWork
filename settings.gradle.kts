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
        // MSAL's common transitive dependency com.microsoft.device.display:display-mask (Surface Duo
        // dual-screen helper library) is published only on Microsoft's own public feed; neither Google nor Maven Central has it.
        // Scoped by group so the remaining dependencies don't all get pointlessly queried against this feed.
        maven {
            url = uri("https://pkgs.dev.azure.com/MicrosoftDeviceSDK/DuoSDK-Public/_packaging/Duo-SDK-Feed/maven/v1")
            content { includeGroup("com.microsoft.device.display") }
        }
    }
}

rootProject.name = "SimplyWork"
include(":app")
