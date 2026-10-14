import app.bestnote.buildlogic.configureKotlinAndroid
import app.bestnote.buildlogic.libs
import app.bestnote.buildlogic.library
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")

        extensions.configure<LibraryExtension> {
            configureKotlinAndroid(this)
            // Namespace mirrors the Gradle path: :core:model -> app.bestnote.core.model
            namespace = "app.bestnote" + path.replace(':', '.').replace('-', '_')
        }

        dependencies {
            add("testImplementation", libs.library("junit4"))
        }
    }
}
