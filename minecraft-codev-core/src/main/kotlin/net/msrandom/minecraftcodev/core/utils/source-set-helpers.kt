package net.msrandom.minecraftcodev.core.utils

import org.apache.commons.lang3.StringUtils
import org.gradle.api.Project
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.util.internal.GUtil

val String.asNamePart
    get() = takeIf { it != SourceSet.MAIN_SOURCE_SET_NAME }.orEmpty()

fun SourceSet.disambiguateName(elementName: String) = lowerCamelCaseGradleName(name.asNamePart, elementName)

fun lowerCamelCaseName(vararg nameParts: String?): String {
    val nonEmptyParts = nameParts.mapNotNull { it?.takeIf(String::isNotEmpty) }

    return nonEmptyParts.drop(1).joinToString(
        separator = "",
        prefix = nonEmptyParts.firstOrNull().orEmpty(),
        transform = StringUtils::capitalize,
    )
}

fun lowerCamelCaseGradleName(vararg nameParts: String?): String =
    GUtil.toLowerCamelCase(lowerCamelCaseName(*nameParts))

fun Project.createSourceSetElements(sourceSetHandler: (sourceSet: SourceSet) -> Unit) {
    extension<SourceSetContainer>().all(sourceSetHandler)
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
