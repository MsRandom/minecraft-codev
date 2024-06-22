package net.msrandom.minecraftcodev.decompiler.resolve

import net.msrandom.minecraftcodev.core.resolve.MinecraftComponentResolvers.Companion.addNamed
import net.msrandom.minecraftcodev.core.resolve.PassthroughArtifactMetadata
import net.msrandom.minecraftcodev.core.utils.getAttribute
import net.msrandom.minecraftcodev.gradle.api.*
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.DocsType
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.capabilities.Capability
import org.gradle.api.internal.attributes.ImmutableAttributes
import org.gradle.api.internal.attributes.ImmutableAttributesFactory
import org.gradle.api.internal.model.NamedObjectInstantiator
import org.gradle.api.plugins.JavaPlugin
import org.gradle.internal.DisplayName
import org.gradle.internal.component.external.model.ImmutableCapabilities
import org.gradle.internal.component.external.model.ModuleComponentArtifactMetadata
import org.gradle.internal.component.model.ComponentArtifactMetadata
import org.gradle.internal.component.model.DependencyMetadata
import javax.inject.Inject

open class DecompiledComponentMetadataDelegate
@Inject
constructor(
    identifier: DecompiledComponentIdentifier,
    private val attributesFactory: ImmutableAttributesFactory,
    private val instantiator: NamedObjectInstantiator,
) : DelegateComponentMetadataHolder<DecompiledComponentIdentifier, DecompiledComponentMetadataDelegate>(identifier) {
    override val type: Class<DecompiledComponentMetadataDelegate>
        get() = DecompiledComponentMetadataDelegate::class.java

    private fun wrapArtifact(artifact: ComponentArtifactMetadata): ComponentArtifactMetadata =
        if (artifact.name.type == ArtifactTypeDefinition.JAR_TYPE) {
            // TODO This is incorrect, as the wrapped artifact is a sources Jar, not a library Jar.
            DecompiledComponentArtifactMetadata(
                artifact as ModuleComponentArtifactMetadata,
                id,
            )
        } else {
            PassthroughArtifactMetadata(artifact)
        }

    override fun wrapVariant(
        variant: VariantMetadataHolder,
        artifactProvider: ArtifactProvider,
    ): VariantMetadata {
        val category = variant.attributes.getAttribute(Category.CATEGORY_ATTRIBUTE.name)
        val docsType = variant.attributes.getAttribute(DocsType.DOCS_TYPE_ATTRIBUTE.name)

        return if (category == Category.DOCUMENTATION && docsType == DocsType.SOURCES) {
            WrappedVariantMetadata(
                variant.name,
                id,
                variant.attributes,
                object : DisplayName {
                    override fun getDisplayName() = "${variant.displayName.displayName} + generated sources"

                    override fun getCapitalizedDisplayName() = "${variant.displayName.capitalizedDisplayName} + Generated Sources"
                },
                emptyList(),
                variant.artifacts.map(::wrapArtifact),
            )
        } else {
            variant
        }
    }

    override fun extraVariants(existing: List<VariantMetadataHolder>): List<VariantMetadata> {
        val configurationsByArtifact =
            existing.flatMap { configuration -> configuration.artifacts.map { it to configuration } }
                .filter { (artifact) -> artifact.componentId is ModuleComponentIdentifier }
                .groupBy { (artifact) -> artifact.name }
                .filterKeys { it.type == ArtifactTypeDefinition.JAR_TYPE }
                .filterValues { configurations ->
                    configurations.any { (_, configuration) ->
                        configuration.attributes.getAttribute(Category.CATEGORY_ATTRIBUTE.name) == Category.LIBRARY &&
                            configuration.attributes.getAttribute(
                                LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE.name,
                            ) == LibraryElements.JAR
                    }
                }

        fun fromConfigurations(
            name: String,
            configurations: List<Pair<ComponentArtifactMetadata, VariantMetadataHolder>>,
        ): VariantMetadataHolder {
            val (artifact, configuration) = configurations.first()

            return if (configurations.size == 1) {
                VariantMetadataHolder(
                    name,
                    artifact.componentId as ModuleComponentIdentifier,
                    configuration.displayName,
                    emptyList(),
                    listOf(wrapArtifact(artifact)),
                    configuration.attributes
                        .addNamed(attributesFactory, instantiator, Category.CATEGORY_ATTRIBUTE, Category.DOCUMENTATION)
                        .addNamed(attributesFactory, instantiator, DocsType.DOCS_TYPE_ATTRIBUTE, DocsType.SOURCES),
                    ImmutableCapabilities.of(configuration.capabilities),
                    setOf(name),
                )
            } else {
                var dependencies: Collection<DependencyMetadata> = configuration.dependencies
                var attributes: Collection<Pair<Attribute<*>, Any?>> = configuration.attributes.asMap().toList()
                var capabilities: Collection<Capability> = configuration.capabilities.capabilities

                for ((_, otherConfiguration) in configurations.drop(1)) {
                    dependencies = dependencies intersect otherConfiguration.dependencies.toSet()
                    attributes = attributes intersect otherConfiguration.attributes.asMap().toList().toSet()
                    capabilities = capabilities intersect otherConfiguration.capabilities.capabilities.toSet()
                }

                var immutableAttributes = ImmutableAttributes.EMPTY

                for ((attribute, value) in attributes) {
                    @Suppress("UNCHECKED_CAST", "NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
                    immutableAttributes =
                        attributesFactory.concat(
                            immutableAttributes,
                            attributesFactory.of(attribute as Attribute<Any?>, value),
                        )
                }

                VariantMetadataHolder(
                    name,
                    artifact.componentId as ModuleComponentIdentifier,
                    configuration.displayName,
                    emptyList(),
                    listOf(wrapArtifact(artifact)),
                    immutableAttributes
                        .addNamed(attributesFactory, instantiator, Category.CATEGORY_ATTRIBUTE, Category.DOCUMENTATION)
                        .addNamed(attributesFactory, instantiator, DocsType.DOCS_TYPE_ATTRIBUTE, DocsType.SOURCES),
                    ImmutableCapabilities.of(capabilities),
                    setOf(name),
                )
            }
        }

        val sourceConfigurations =
            if (configurationsByArtifact.size == 1) {
                listOf(
                    fromConfigurations(
                        JavaPlugin.SOURCES_ELEMENTS_CONFIGURATION_NAME,
                        configurationsByArtifact.iterator().next().value,
                    ),
                )
            } else {
                configurationsByArtifact.map {
                    fromConfigurations("${it.key}-sources", it.value)
                }
            }

        return sourceConfigurations
    }
}
