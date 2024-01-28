package net.msrandom.minecraftcodev.gradle.api

import org.gradle.api.artifacts.ModuleVersionIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.internal.attributes.ImmutableAttributes
import org.gradle.internal.component.model.ComponentArtifactMetadata

data class ComponentMetadataHolder(
    val id: ModuleComponentIdentifier,
    val attributes: ImmutableAttributes,
    val moduleVersionId: ModuleVersionIdentifier,
    val variants: List<VariantMetadataHolder>,
    val isChanging: Boolean,
    val status: String,
    val statusScheme: List<String>,
)

data class DelegateComponentMetadataHolder(
    val id: ModuleComponentIdentifier,
    val variants: (List<VariantMetadataHolder>) -> List<VariantMetadataHolder>,
    val variant: (VariantMetadataHolder) -> VariantMetadataHolder,
)
