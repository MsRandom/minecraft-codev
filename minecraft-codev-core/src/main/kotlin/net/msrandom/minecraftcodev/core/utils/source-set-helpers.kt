package net.msrandom.minecraftcodev.core.utils

import org.apache.commons.lang3.StringUtils
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer

val String.asNamePart
    get() = takeIf { it != SourceSet.MAIN_SOURCE_SET_NAME }.orEmpty()

fun SourceSet.disambiguateName(elementName: String) = lowerCamelCaseName(name.asNamePart, elementName)

fun lowerCamelCaseName(vararg nameParts: String?): String {
    val nonEmptyParts = nameParts.mapNotNull { it?.takeIf(String::isNotEmpty) }

    return nonEmptyParts.drop(1).joinToString(
        separator = "",
        prefix = nonEmptyParts.firstOrNull().orEmpty(),
        transform = StringUtils::capitalize,
    )
}

fun Project.createSourceSetElements(sourceSetHandler: (sourceSet: SourceSet) -> Unit) {
    plugins.withType(JavaPlugin::class.java) {
        extensions.getByType(SourceSetContainer::class.java).all(sourceSetHandler)
    }
}

fun Project.createSourceSetConfigurations(
    name: String,
    transitive: Boolean = false,
) {
    fun createConfiguration(name: String) =
        configurations.maybeCreate(name).apply {
            isCanBeConsumed = false
            isTransitive = transitive
        }

    createSourceSetElements {
        createConfiguration(it.disambiguateName(name))
    }
}
