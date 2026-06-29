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
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/ccolorcat/k-mvi")
            credentials {
                username = providers.gradleProperty("gpr.personal.user").orNull ?: System.getenv("USERNAME") ?: ""
                password = providers.gradleProperty("gpr.personal.key").orNull ?: System.getenv("TOKEN") ?: ""
            }
        }
    }
}

rootProject.name = "k-mvi"
include(":app")
include(":core")
