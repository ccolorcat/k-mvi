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
                username = extra["gpr.personal.user"]?.toString() ?: System.getenv("USERNAME")
                password = extra["gpr.personal.key"]?.toString() ?: System.getenv("TOKEN")
            }
        }
    }
}

rootProject.name = "k-mvi"
include(":app")
include(":core")
