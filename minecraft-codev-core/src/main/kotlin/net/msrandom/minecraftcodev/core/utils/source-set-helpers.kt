package net.msrandom.minecraftcodev.core.utils

import net.msrandom.minecraftcodev.core.dependency.ConfiguredDependencyMetadata
import org.apache.commons.lang3.StringUtils
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.jetbrains.kotlin.commonizer.util.transitiveClosure
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinMultiplatformPluginWrapper
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSetContainer
import org.jetbrains.kotlin.gradle.plugin.KotlinTargetsContainer
import org.jetbrains.kotlin.gradle.utils.extendsFrom

/**
 * Find a matching configuration name by attempting to find which source set the resolving module configuration is from.
 */
fun Project.getSourceSetConfigurationName(dependency: ConfiguredDependencyMetadata, defaultConfiguration: String) = dependency.relatedConfiguration ?: run {
    val moduleConfiguration = dependency.getModuleConfiguration()
    if (moduleConfiguration == null) {
        defaultConfiguration
    } else {
        // First, look for a Java source set. We can't know if this is an exact match as Java source sets don't keep a list of related configurations, but it's good enough.
        var owningSourceSetName = extensions
            .findByType(SourceSetContainer::class.java)
            ?.firstOrNull { moduleConfiguration.startsWith(it.name) }
            ?.name

        if (owningSourceSetName == null) {
            // If that fails, look for a matching Kotlin source set.
            owningSourceSetName = extensions
                .findByType(KotlinSourceSetContainer::class.java)
                ?.sourceSets
                ?.firstOrNull { moduleConfiguration in it.relatedConfigurationNames }
                ?.name

            if (owningSourceSetName == null) {
                // If all fails, look for a matching Kotlin compilation as a last resort.
                owningSourceSetName = extensions
                    .findByType(KotlinTargetsContainer::class.java)
                    ?.targets
                    ?.firstNotNullOfOrNull { target ->
                        target.compilations.firstOrNull {
                            moduleConfiguration in it.relatedConfigurationNames
                        }
                    }
                    ?.name
            }
        }

        if (owningSourceSetName == SourceSet.MAIN_SOURCE_SET_NAME) {
            owningSourceSetName = null
        }

        // If no related source set/compilation was found, we can return the default configuration, and it will error later if it doesn't exist.
        owningSourceSetName?.let { it + StringUtils.capitalize(defaultConfiguration) } ?: defaultConfiguration
    }
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

fun Project.createSourceSetConfigurations(name: String) {
    createSourceSetElements { setName, _ ->
        val configurationName = if (setName.isEmpty()) name else setName + StringUtils.capitalize(name)
        configurations.maybeCreate(configurationName).apply {
            isCanBeConsumed = false
            isTransitive = false
        }
    }

    extendKotlinConfigurations(name)
}

fun Project.extendKotlinConfigurations(name: String) {
    plugins.withType(KotlinMultiplatformPluginWrapper::class.java) {
        afterEvaluate {
            extensions.getByType(KotlinTargetsContainer::class.java).targets.all { target ->
                val capitalized = StringUtils.capitalize(name)

                target.compilations.all { compilation ->
                    val defaultSourceSet = compilation.defaultSourceSet

                    val configurationName = if (defaultSourceSet.name == SourceSet.MAIN_SOURCE_SET_NAME) {
                        name
                    } else {
                        defaultSourceSet.name + capitalized
                    }

                    val configuration = configurations.named(configurationName)

                    val allSourceSets = compilation.kotlinSourceSets + compilation.kotlinSourceSets.flatMapTo(mutableSetOf()) {
                        transitiveClosure(defaultSourceSet) { dependsOn }
                    }

                    for (sourceSet in allSourceSets) {
                        if (sourceSet != defaultSourceSet) {
                            if (sourceSet.name == SourceSet.MAIN_SOURCE_SET_NAME) {
                                configuration.extendsFrom(configurations.named(name))
                            } else {
                                configuration.extendsFrom(configurations.named(sourceSet.name + capitalized))
                            }
                        }
                    }
                }
            }
        }
    }
}
