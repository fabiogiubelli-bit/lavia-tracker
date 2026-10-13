pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// Token segreto Mapbox (DOWNLOADS:READ): serve solo se il repository Maven di Mapbox
// risponde 401 durante la build. Normalmente non è necessario.
val mapboxDownloadsToken: String =
    providers.environmentVariable("MAPBOX_DOWNLOADS_TOKEN").orNull
        ?: providers.gradleProperty("MAPBOX_DOWNLOADS_TOKEN").orNull
        ?: ""

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://api.mapbox.com/downloads/v2/releases/maven")
            if (mapboxDownloadsToken.isNotBlank()) {
                authentication { create<BasicAuthentication>("basic") }
                credentials {
                    username = "mapbox"
                    password = mapboxDownloadsToken
                }
            }
        }
    }
}

rootProject.name = "lavia-tracker"
include(":app")
