package net.msrandom.minecraftcodev.gradle.api

import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.capabilities.CapabilitiesMetadata
import org.gradle.api.internal.attributes.ImmutableAttributes
import org.gradle.internal.DisplayName
import org.gradle.internal.component.external.model.ModuleDependencyMetadata
import org.gradle.internal.component.model.ComponentArtifactMetadata
import org.gradle.internal.component.model.DependencyMetadata

data class VariantMetadataHolder(
    val name: String,
    val componentId: ModuleComponentIdentifier,
    val dependencies: List<ModuleDependencyMetadata>,
    val artifacts: List<ComponentArtifactMetadata>,
    val attributes: ImmutableAttributes,
    val capabilities: CapabilitiesMetadata,
    val hierarchy: Set<String>,
)

data class DelegateVariantMetadataHolder(
    val describable: (DisplayName) -> DisplayName,
    val attributes: ImmutableAttributes,
    val dependencies: (List<DependencyMetadata>) -> List<DependencyMetadata>,
    val artifacts: (List<ComponentArtifactMetadata>) -> List<ComponentArtifactMetadata>,
)
