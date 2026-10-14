import app.bestnote.buildlogic.configureKotlinJvm
import app.bestnote.buildlogic.libs
import app.bestnote.buildlogic.library
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/** Pure Kotlin/JVM module with no Android dependency. */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")
        configureKotlinJvm()

        dependencies {
            add("testImplementation", libs.library("junit4"))
        }
    }
}
