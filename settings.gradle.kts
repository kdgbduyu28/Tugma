pluginManagement {
    repositories {
        google()
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

rootProject.name = "tugma"

include(":core")
include(":upnp-core")
include(":sonos-core")
include(":cast-core")
include(":eq-core")
include(":bt-core")
include(":linkplay-core")
include(":cli")
include(":app")
