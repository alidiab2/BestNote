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
include(":core:updater")
include(":core:fonts")
include(":core:security")

include(":engine:stylus")
include(":engine:ink")
include(":engine:canvas")
include(":engine:pdf")
include(":engine:recognition")
include(":engine:recognition-mlkit")
include(":engine:layout")
include(":engine:handwriting")
include(":engine:export")
include(":engine:templates")

include(":ai:math")
include(":ai:vision-api")
include(":ai:vision-gemini")
include(":ai:vision-claude")
include(":ai:vision-openai")
include(":ai:vision-providers")
include(":ai:board-scan")

include(":feature:library")
include(":feature:onboarding")
include(":feature:editor")
include(":feature:settings")
include(":feature:smartboard")
include(":feature:templates")
