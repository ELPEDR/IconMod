pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositories { google(); mavenCentral(); maven("https://api.xposed.info/") }
}
rootProject.name = "IconMod"
include(":app")
