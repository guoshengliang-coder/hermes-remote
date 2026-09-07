pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // MissionGo feedback SDK. Static files, anonymous read, no credentials. Scoped to its own
        // group so no other dependency pays a network round-trip to a host that only serves this
        // one artifact — and so a lookup there can never shadow Maven Central.
        maven {
            name = "missiongo"
            url = uri("https://missiongo.mrlgs.net/maven")
            content { includeGroup("io.missiongo") }
        }
    }
}
rootProject.name = "HermesRemoteAndroid"
include(":app")
