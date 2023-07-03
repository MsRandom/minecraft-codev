package net.msrandom.minecraftcodev.core.utils

import org.apache.commons.lang3.StringUtils
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.jetbrains.kotlin.gradle.plugin.*

val HasKotlinDependencies.sourceSetName: String
    get() = (this as? KotlinCompilation<*>)?.defaultSourceSetName ?: (this as KotlinSourceSet).name

fun sourceSetName(sourceSet: String, configuration: String) = if (sourceSet == SourceSet.MAIN_SOURCE_SET_NAME) {
    configuration
} else {
    "$sourceSet${StringUtils.capitalize(configuration)}"
}

fun Project.createSourceSetElements(action: (name: String, isSourceSet: Boolean) -> Unit) {
    plugins.withType(JavaPlugin::class.java) {
        action("", true)

        extensions.getByType(SourceSetContainer::class.java).all { sourceSet ->
            @Suppress("UnstableApiUsage")
            if (!SourceSet.isMain(sourceSet)) {
                action(sourceSet.name, true)
            }
        }
    }

    plugins.withType(KotlinMultiplatformPluginWrapper::class.java) {
        extensions.getByType(KotlinSourceSetContainer::class.java).sourceSets.all { sourceSet ->
            if (sourceSet.name == SourceSet.MAIN_SOURCE_SET_NAME) {
                action("", true)
            } else {
                action(sourceSet.name, true)
            }
        }

        extensions.getByType(KotlinTargetsContainer::class.java).targets.all { target ->
            target.compilations.all { compilation ->
                val compilationConfigurationName = target.disambiguationClassifier +
                        compilation.name.takeIf { it != KotlinCompilation.MAIN_COMPILATION_NAME }?.let(StringUtils::capitalize).orEmpty()

                action(compilationConfigurationName, false)
            }
        }
    }
}

fun Project.createSourceSetConfigurations(name: String, transitive: Boolean = false) {
    createSourceSetElements { setName, _ ->
        val configurationName = if (setName.isEmpty()) name else setName + StringUtils.capitalize(name)
        configurations.maybeCreate(configurationName).apply {
            isCanBeConsumed = false
            isTransitive = transitive
        }
    }
}
