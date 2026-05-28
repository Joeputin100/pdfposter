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
rootProject.name = "pdfposter"
include(":app")
// RC41: Macrobenchmark module — startup + scroll-jank metrics. Builds only
// when explicitly requested (variant = "benchmark") so normal `gradle build`
// flows ignore it.
include(":benchmark")
