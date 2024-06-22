package net.msrandom.minecraftcodev.remapper.resolve

import net.msrandom.minecraftcodev.core.MappingsNamespace
import net.msrandom.minecraftcodev.core.resolve.MinecraftComponentIdentifier
import net.msrandom.minecraftcodev.core.resolve.MinecraftComponentResolvers.Companion.addNamed
import net.msrandom.minecraftcodev.core.resolve.PassthroughArtifactMetadata
import net.msrandom.minecraftcodev.core.utils.named
import net.msrandom.minecraftcodev.gradle.api.*
import net.msrandom.minecraftcodev.remapper.dependency.RemappedDependencyMetadataWrapper
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.internal.attributes.AttributeContainerInternal
import org.gradle.api.internal.attributes.AttributeValue
import org.gradle.api.internal.attributes.ImmutableAttributesFactory
import org.gradle.api.internal.model.NamedObjectInstantiator
import org.gradle.api.model.ObjectFactory
import org.gradle.internal.DisplayName
import org.gradle.internal.component.external.model.DefaultModuleComponentSelector
import org.gradle.internal.component.external.model.ModuleComponentArtifactMetadata
import javax.inject.Inject

open class RemappedComponentMetadataDelegate
@Inject
constructor(
    identifier: RemappedComponentIdentifier,
    private val attributesFactory: ImmutableAttributesFactory,
    private val instantiator: NamedObjectInstantiator,
    private val objectFactory: ObjectFactory,
    private val project: Project,
) : DelegateComponentMetadataHolder<RemappedComponentIdentifier, RemappedComponentMetadataDelegate>(identifier) {
    override val type: Class<RemappedComponentMetadataDelegate>
        get() = RemappedComponentMetadataDelegate::class.java

    override fun wrapVariant(
        variant: VariantMetadataHolder,
        artifactProvider: ArtifactProvider,
    ): VariantMetadata {
        val sourceNamespace =
            variant.attributes.findEntry(
                MappingsNamespace.attribute.name,
            ).takeIf(AttributeValue<*>::isPresent)?.get() as? String

        val category = variant.attributes.findEntry(Category.CATEGORY_ATTRIBUTE.name)
        val libraryElements = variant.attributes.findEntry(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE.name)

        return if (category.isPresent && libraryElements.isPresent && category.get() == Category.LIBRARY && libraryElements.get() == LibraryElements.JAR) {
            val mappedArtifacts =
                variant.artifacts.map {
                    if (it.name.type == ArtifactTypeDefinition.JAR_TYPE) {
                        RemappedComponentArtifactMetadata(
                            it as ModuleComponentArtifactMetadata,
                            id,
                            sourceNamespace.orEmpty(),
                            project,
                        )
                    } else {
                        PassthroughArtifactMetadata(it)
                    }
                }

            val artifacts =
                if (id.original is MinecraftComponentIdentifier && (id.original as MinecraftComponentIdentifier).isBase) {
                    // Add the mappings file if we're remapping Minecraft.
                    mappedArtifacts + MappingsArtifact(id, id.mappingsConfiguration, project)
                } else {
                    mappedArtifacts
                }

            WrappedVariantMetadata(
                variant.name,
                id,
                variant.attributes.addNamed(attributesFactory, instantiator, MappingsNamespace.attribute, id.targetNamespace),
                object : DisplayName {
                    override fun getDisplayName() = "remapped ${variant.displayName.displayName}"

                    override fun getCapitalizedDisplayName() = "Remapped ${variant.displayName.capitalizedDisplayName}"
                },
                variant.dependencies.map { dependency ->
                    val namespace = dependency.selector.attributes.getAttribute(MappingsNamespace.attribute)

                    val selector =
                        DefaultModuleComponentSelector.withAttributes(
                            dependency.selector as ModuleComponentSelector, // Unsafe assumption, should be changed if we want things to be more generic.
                            attributesFactory.concat(
                                (dependency.selector.attributes as AttributeContainerInternal).asImmutable(),
                                attributesFactory.of(MappingsNamespace.attribute, objectFactory.named(id.targetNamespace)),
                            ),
                        )

                    RemappedDependencyMetadataWrapper(
                        dependency,
                        selector,
                        id.sourceNamespace.takeUnless(String::isEmpty) ?: namespace?.name.orEmpty(),
                        id.targetNamespace,
                        id.mappingsConfiguration,
                    )
                },
                artifacts,
            )
        } else {
            variant
        }
    }
}
