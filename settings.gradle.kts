pluginManagement {
    includeBuild("build-logic")
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
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "BestNote"

include(":app")

include(":core:model")
include(":core:common")
include(":core:designsystem")
include(":core:ui")
include(":core:database")
include(":core:datastore")
include(":core:storage")

include(":engine:stylus")
include(":engine:ink")
include(":engine:canvas")
include(":engine:pdf")
include(":engine:recognition")
include(":engine:layout")
include(":engine:handwriting")
include(":engine:export")

include(":ai:math")
include(":ai:vision-api")
include(":ai:vision-gemini")

include(":feature:library")
include(":feature:editor")
include(":feature:settings")
include(":feature:smartboard")
