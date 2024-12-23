package net.msrandom.minecraftcodev.forge

import org.gradle.api.artifacts.CacheableRule
import org.gradle.api.artifacts.ComponentMetadataContext
import org.gradle.api.artifacts.ComponentMetadataDetails
import org.gradle.api.artifacts.ComponentMetadataRule
import org.gradle.api.artifacts.repositories.RepositoryResourceAccessor
import org.gradle.api.attributes.Attribute
import javax.inject.Inject

@JvmField
val CLASSIFIER_ATTRIBUTE: Attribute<String> =
    Attribute.of("net.msrandom.minecraftcodev.forge-library-classifier", String::class.java)

@CacheableRule
abstract class MinecraftForgeComponentClassifierMetadataRule @Inject constructor(private val userdevs: List<UserdevPath>) :
    ComponentMetadataRule {
    abstract val repositoryResourceAccessor: RepositoryResourceAccessor
        @Inject get

    private fun ComponentMetadataDetails.addClassifier(classifier: String) {
        withVariant("compile") {
            it.attributes.attribute(CLASSIFIER_ATTRIBUTE, "")
        }

        withVariant("runtime") {
            it.attributes.attribute(CLASSIFIER_ATTRIBUTE, "")
        }

        withVariant("apiElements") {
            it.attributes.attribute(CLASSIFIER_ATTRIBUTE, "")
        }

        withVariant("runtimeElements") {
            it.attributes.attribute(CLASSIFIER_ATTRIBUTE, "")
        }

        maybeAddVariant("${classifier}-compile", "compile") {
            it.attributes.attribute(CLASSIFIER_ATTRIBUTE, classifier)

            it.withFiles {
                it.removeAllFiles()

                it.addFile("${id.name}-${id.version}-$classifier.jar")
            }
        }

        maybeAddVariant("${classifier}-runtime", "runtime") {
            it.attributes.attribute(CLASSIFIER_ATTRIBUTE, classifier)

            it.withFiles {
                it.removeAllFiles()

                it.addFile("${id.name}-${id.version}-$classifier.jar")
            }
        }

        maybeAddVariant("${classifier}ApiElements", "apiElements") {
            it.attributes.attribute(CLASSIFIER_ATTRIBUTE, classifier)

            it.withFiles {
                it.removeAllFiles()

                it.addFile("${id.name}-${id.version}-$classifier.jar")
            }
        }

        maybeAddVariant("${classifier}RuntimeElements", "runtimeElements") {
            it.attributes.attribute(CLASSIFIER_ATTRIBUTE, classifier)

            it.withFiles {
                it.removeAllFiles()

                it.addFile("${id.name}-${id.version}-$classifier.jar")
            }
        }
    }

    override fun execute(context: ComponentMetadataContext) {
        val id = context.details.id

        val currentUserdev = userdevs.firstOrNull {
            it.group == id.group && it.name == id.name && it.version == id.version
        }

        if (currentUserdev != null) {
            context.details.addClassifier(currentUserdev.classifier)
        }

        val userdevLibraries = userdevs.flatMapTo(hashSetOf()) {
            getUserdev(it, repositoryResourceAccessor)?.libraries ?: emptyList()
        }

        val library = userdevLibraries.firstOrNull { "${id.group}:${id.name}:${id.version}" in it } ?: return

        val classifier = getClassifier(library)

        if (classifier != null) {
            context.details.addClassifier(classifier)
        }
    }
}
