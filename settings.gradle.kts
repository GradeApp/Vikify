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
        flatDir { dirs("app/libs") }
    }
}

rootProject.name = "Vikify"
include(":app")
// Independent UID: probe requests must cross Android's TUN, unlike Vikify itself.
if (providers.gradleProperty("emulator").orNull == "true") include(":vpnprobe")
