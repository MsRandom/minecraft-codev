package net.msrandom.minecraftcodev.accesswidener.resolve

import net.msrandom.minecraftcodev.accesswidener.dependency.AccessWidenedDependencyMetadataWrapper
import net.msrandom.minecraftcodev.core.MappingsNamespace
import net.msrandom.minecraftcodev.core.resolve.PassthroughArtifactMetadata
import net.msrandom.minecraftcodev.core.utils.getAttribute
import net.msrandom.minecraftcodev.gradle.api.*
import org.gradle.api.Project
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.internal.attributes.AttributeValue
import org.gradle.internal.DisplayName
import org.gradle.internal.component.external.model.ModuleComponentArtifactMetadata
import javax.inject.Inject

open class AccessWidenedComponentMetadataDelegate
@Inject
constructor(
    identifier: AccessWidenedComponentIdentifier,
    private val project: Project,
) : DelegateComponentMetadataHolder<AccessWidenedComponentIdentifier, AccessWidenedComponentMetadataDelegate>(identifier) {
    override val type: Class<AccessWidenedComponentMetadataDelegate>
        get() = AccessWidenedComponentMetadataDelegate::class.java

    override fun wrapVariant(
        variant: VariantMetadataHolder,
        artifactProvider: ArtifactProvider,
    ): VariantMetadata {
        val namespace =
            variant.attributes.findEntry(
                MappingsNamespace.attribute.name,
            ).takeIf(AttributeValue<*>::isPresent)?.get() as? String

        return if (variant.attributes.getAttribute(
                Category.CATEGORY_ATTRIBUTE.name,
            ) == Category.LIBRARY && variant.attributes.getAttribute(
                LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE.name,
            ) == LibraryElements.JAR
        ) {
            WrappedVariantMetadata(
                variant.name,
                id,
                variant.attributes,
                object : DisplayName {
                    override fun getDisplayName() = "access widened ${variant.displayName.displayName}"

                    override fun getCapitalizedDisplayName() = "Access Widened ${variant.displayName.capitalizedDisplayName}"
                },
                variant.dependencies.map { dependency ->
                    // Maybe pass the namespace to use instead of the metadata one?
                    AccessWidenedDependencyMetadataWrapper(
                        dependency,
                        id.accessWidenersConfiguration,
                    )
                },
                variant.artifacts.map {
                    if (it.name.type == ArtifactTypeDefinition.JAR_TYPE) {
                        AccessWidenedComponentArtifactMetadata(
                            it as ModuleComponentArtifactMetadata,
                            id,
                            namespace.orEmpty(),
                            project,
                        )
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
