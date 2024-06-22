package net.msrandom.minecraftcodev.gradle.gradle8

import net.msrandom.minecraftcodev.gradle.api.WrappedVariantMetadata
import org.gradle.internal.component.external.model.DefaultModuleComponentArtifactMetadata
import org.gradle.internal.component.model.*
import org.gradle.internal.impldep.com.google.common.collect.ImmutableList
import org.gradle.internal.impldep.com.google.common.collect.ImmutableSet

class DelegateVariantGraphResolveMetadata(
    private val metadata: VariantGraphResolveMetadata,
    private val wrappedVariantData: WrappedVariantMetadata,
) : VariantGraphResolveMetadata, VariantArtifactGraphResolveMetadata, ConfigurationMetadata, VariantResolveMetadata {
    override fun getAttributes() = wrappedVariantData.attributes

    override fun getHierarchy(): ImmutableSet<String> = ImmutableSet.copyOf((metadata as ConfigurationMetadata).hierarchy)

    override fun getName() = metadata.name

    override fun asDescribable() = wrappedVariantData.displayName

    override fun getVariants() = setOf(this)

    override fun getDependencies() = wrappedVariantData.dependencies

    override fun getExcludes(): ImmutableList<ExcludeMetadata> = ImmutableList.copyOf(metadata.excludes)

    override fun getCapabilities() = metadata.capabilities

    override fun getIdentifier() = (metadata as VariantResolveMetadata).identifier

    override fun isTransitive() = metadata.isTransitive

    override fun isVisible() = (metadata as ConfigurationMetadata).isVisible

    override fun isCanBeConsumed() = (metadata as ConfigurationMetadata).isCanBeConsumed

    override fun isCanBeResolved() = (metadata as ConfigurationMetadata).isCanBeResolved

    override fun artifact(artifact: IvyArtifactName) =
        DefaultModuleComponentArtifactMetadata(
            wrappedVariantData.componentId,
            artifact,
        )

    override fun isExternalVariant() = metadata.isExternalVariant

    override fun getArtifacts(): ImmutableList<ComponentArtifactMetadata> = ImmutableList.copyOf(wrappedVariantData.artifacts)
}
