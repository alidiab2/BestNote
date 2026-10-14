import app.bestnote.buildlogic.libs
import app.bestnote.buildlogic.library
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/** A feature module: Android library + Compose + Hilt + type-safe navigation, wired to core:model and core:common. */
class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("bestnote.android.library")
        pluginManager.apply("bestnote.android.compose")
        pluginManager.apply("bestnote.android.hilt")
        // Type-safe navigation routes are @Serializable objects.
        pluginManager.apply("org.jetbrains.kotlin.plugin.serialization")

        dependencies {
            add("implementation", project(":core:common"))
            add("implementation", project(":core:model"))
            add("implementation", libs.library("androidx-lifecycle-runtime-compose"))
            add("implementation", libs.library("androidx-lifecycle-viewmodel-compose"))
            add("implementation", libs.library("androidx-navigation-compose"))
            add("implementation", libs.library("androidx-hilt-lifecycle-viewmodel-compose"))
            add("implementation", libs.library("kotlinx-serialization-json"))
            add("testImplementation", libs.library("kotlinx-coroutines-test"))
        }
    }
}
