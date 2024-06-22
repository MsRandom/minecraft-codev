package net.msrandom.minecraftcodev.mixins.resolve

import net.msrandom.minecraftcodev.core.MappingsNamespace
import net.msrandom.minecraftcodev.core.resolve.PassthroughArtifactMetadata
import net.msrandom.minecraftcodev.gradle.api.*
import net.msrandom.minecraftcodev.mixins.dependency.MixinDependencyMetadataWrapper
import org.gradle.api.Project
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.internal.DisplayName
import org.gradle.internal.component.external.model.ModuleComponentArtifactMetadata
import javax.inject.Inject

open class MixinComponentMetadataDelegate
@Inject
constructor(
    identifier: MixinComponentIdentifier,
    private val project: Project,
) : DelegateComponentMetadataHolder<MixinComponentIdentifier, MixinComponentMetadataDelegate>(identifier) {
    override val type: Class<MixinComponentMetadataDelegate>
        get() = MixinComponentMetadataDelegate::class.java

    override fun wrapVariant(
        variant: VariantMetadataHolder,
        artifactProvider: ArtifactProvider,
    ): VariantMetadata {
        val category = variant.attributes.findEntry(Category.CATEGORY_ATTRIBUTE.name)

        val shouldWrap =
            if (category.isPresent && category.get() == Category.LIBRARY) {
                val libraryElements = variant.attributes.findEntry(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE.name)
                libraryElements.isPresent && libraryElements.get() == LibraryElements.JAR
            } else {
                false
            }

        return if (shouldWrap) {
            WrappedVariantMetadata(
                variant.name,
                id,
                variant.attributes,
                object : DisplayName {
                    override fun getDisplayName() = "mixin ${variant.displayName.displayName}"

                    override fun getCapitalizedDisplayName() = "Mixin ${variant.displayName.capitalizedDisplayName}"
                },
                variant.dependencies.map { dependency ->
                    if (dependency.selector.attributes.getAttribute(MappingsNamespace.attribute) != null) {
                        MixinDependencyMetadataWrapper(dependency, id.mixinsConfiguration)
                    } else {
                        dependency
                    }
                },
                variant.artifacts.map {
                    if (it.name.type == ArtifactTypeDefinition.JAR_TYPE) {
                        MixinComponentArtifactMetadata(this as ModuleComponentArtifactMetadata, id, project)
                    } else {
                        PassthroughArtifactMetadata(it)
                    }
                },
            )
        } else {
            variant
        }
    }
}
