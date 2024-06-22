package net.msrandom.minecraftcodev.forge.resolve

import net.msrandom.minecraftcodev.forge.dependency.FmlLoaderWrappedComponentIdentifier
import net.msrandom.minecraftcodev.gradle.api.ArtifactProvider
import net.msrandom.minecraftcodev.gradle.api.DelegateComponentMetadataHolder
import net.msrandom.minecraftcodev.gradle.api.VariantMetadataHolder
import net.msrandom.minecraftcodev.gradle.api.WrappedVariantMetadata
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.internal.component.external.model.ModuleComponentArtifactMetadata
import javax.inject.Inject

open class FmlLoaderComponentMetadataDelegate
@Inject
constructor(
    identifier: FmlLoaderWrappedComponentIdentifier,
) : DelegateComponentMetadataHolder<FmlLoaderWrappedComponentIdentifier, FmlLoaderComponentMetadataDelegate>(identifier) {
    override val type: Class<FmlLoaderComponentMetadataDelegate>
        get() = FmlLoaderComponentMetadataDelegate::class.java

    override fun wrapVariant(
        variant: VariantMetadataHolder,
        artifactProvider: ArtifactProvider,
    ) = WrappedVariantMetadata(
        variant.name,
        id,
        variant.attributes,
        variant.displayName,
        variant.dependencies,
        variant.artifacts.map {
            if (it is ModuleComponentArtifactMetadata && it.name.type == ArtifactTypeDefinition.JAR_TYPE) {
                FmlLoaderWrappedMetadata(it, id)
            } else {
                it
            }
        },
    )
}
