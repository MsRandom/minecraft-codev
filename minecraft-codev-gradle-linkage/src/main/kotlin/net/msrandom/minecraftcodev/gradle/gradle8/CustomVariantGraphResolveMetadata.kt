package net.msrandom.minecraftcodev.gradle.gradle8

import net.msrandom.minecraftcodev.gradle.api.VariantMetadataHolder
import org.gradle.internal.Describables
import org.gradle.internal.DisplayName
import org.gradle.internal.component.external.model.DefaultModuleComponentArtifactMetadata
import org.gradle.internal.component.model.*
import org.gradle.internal.impldep.com.google.common.collect.ImmutableList
import org.gradle.internal.impldep.com.google.common.collect.ImmutableSet

class CustomVariantGraphResolveMetadata(private val metadata: VariantMetadataHolder) : VariantGraphResolveMetadata, VariantArtifactGraphResolveMetadata, ModuleConfigurationMetadata {
    override fun getAttributes() = metadata.attributes
    override fun getHierarchy(): ImmutableSet<String> = ImmutableSet.copyOf(metadata.hierarchy)
    override fun getName() = metadata.name
    override fun asDescribable(): DisplayName = Describables.of(metadata.componentId, "variant", name)
    override fun getVariants() = emptySet<VariantResolveMetadata>()
    override fun getDependencies() = metadata.dependencies
    override fun getExcludes(): ImmutableList<ExcludeMetadata> = ImmutableList.of()
    override fun getCapabilities() = metadata.capabilities
    override fun isTransitive() = true
    override fun isVisible() = true
    override fun isCanBeConsumed() = true
    override fun isCanBeResolved() = false
    override fun artifact(artifact: IvyArtifactName) = DefaultModuleComponentArtifactMetadata(metadata.componentId, artifact)
    override fun isExternalVariant() = false
    override fun getArtifacts(): ImmutableList<ComponentArtifactMetadata> = ImmutableList.copyOf(metadata.artifacts)
}
