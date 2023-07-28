package net.msrandom.minecraftcodev.core.utils

import org.apache.commons.lang3.StringUtils
import org.gradle.api.Named
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.jetbrains.kotlin.gradle.plugin.*

val String.asNamePart
    get() = takeIf { it != SourceSet.MAIN_SOURCE_SET_NAME }.orEmpty()

fun SourceSet.disambiguateName(elementName: String) =
    lowerCamelCaseName(name.asNamePart, elementName)

fun HasKotlinDependencies.disambiguateName(elementName: String) = if (this is KotlinCompilation<*>) {
    lowerCamelCaseName(target.disambiguationClassifier, name.asNamePart, elementName)
} else {
    lowerCamelCaseName((this as Named).name, elementName)
}

fun KotlinTarget.disambiguateName(elementName: String) =
    lowerCamelCaseName(targetName, elementName)

fun lowerCamelCaseName(vararg nameParts: String?): String {
    val nonEmptyParts = nameParts.mapNotNull { it?.takeIf(String::isNotEmpty) }

    return nonEmptyParts.drop(1).joinToString(
        separator = "",
        prefix = nonEmptyParts.firstOrNull().orEmpty(),
        transform = StringUtils::capitalize
    )
}

fun Project.createSourceSetElements(
    sourceSetHandler: (sourceSet: SourceSet) -> Unit,
    kotlinTargetHandler: (target: KotlinTarget) -> Unit,
    kotlinCompilationHandler: (compilation: KotlinCompilation<*>) -> Unit,
    kotlinSourceSetHandler: (sourceSet: KotlinSourceSet) -> Unit
) {
    plugins.withType(JavaPlugin::class.java) {
        extensions.getByType(SourceSetContainer::class.java).all(sourceSetHandler)
    }

    plugins.withType(KotlinMultiplatformPluginWrapper::class.java) {
        extensions.getByType(KotlinSourceSetContainer::class.java).sourceSets.all(kotlinSourceSetHandler)

        extensions.getByType(KotlinTargetsContainer::class.java).targets.all { target ->
            kotlinTargetHandler(target)

            target.compilations.all(kotlinCompilationHandler)
        }
    }
}

fun Project.createCompilationConfigurations(name: String, transitive: Boolean = false) {
    fun createConfiguration(name: String) = configurations.maybeCreate(name).apply {
        isCanBeConsumed = false
        isTransitive = transitive
    }

    createSourceSetElements({
        createConfiguration(it.disambiguateName(name))
    }, {}, {
        createConfiguration(it.disambiguateName(name))
    }, {
        createConfiguration(it.disambiguateName(name))
    })
}

fun Project.createTargetConfigurations(name: String, transitive: Boolean = false) {
    fun createConfiguration(name: String, bucket: Boolean = false) = configurations.maybeCreate(name).apply {
        if (bucket) {
            isCanBeResolved = false
        }

        isCanBeConsumed = false
        isTransitive = transitive
    }

    createSourceSetElements({
        createConfiguration(it.disambiguateName(name))
    }, {}, {
        val targetConfiguration = createConfiguration(it.target.disambiguateName(name))
        val compilationConfigurationName = it.defaultSourceSet.disambiguateName(name)

        if (targetConfiguration.name != compilationConfigurationName) {
            targetConfiguration.extendsFrom(createConfiguration(compilationConfigurationName, true))
        }
    }, {
        createConfiguration(it.disambiguateName(name))
    })
}

