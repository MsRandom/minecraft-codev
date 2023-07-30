package net.msrandom.minecraftcodev.gradle

import org.gradle.api.internal.attributes.ImmutableAttributes
import org.gradle.internal.DisplayName
import org.gradle.internal.component.model.*
import org.gradle.internal.impldep.com.google.common.collect.ImmutableList
import javax.inject.Inject

@Suppress("unused")
internal open class DelegatingConfigurationMetadata @Inject constructor(
    private val delegate: ConfigurationMetadata,
    private val describable: (DisplayName) -> DisplayName,
    private val attributes: ImmutableAttributes,
    private val dependencies: (List<DependencyMetadata>) -> List<DependencyMetadata>,
    private val artifacts: (List<ComponentArtifactMetadata>) -> List<ComponentArtifactMetadata>,
    private val artifact: (ComponentArtifactMetadata) -> ComponentArtifactMetadata
) : ConfigurationMetadata by delegate {
    override fun asDescribable() = describable(delegate.asDescribable())
    override fun getAttributes() = attributes
    override fun getDependencies() = dependencies(delegate.dependencies)
    override fun getArtifacts(): ImmutableList<ComponentArtifactMetadata> = ImmutableList.copyOf(artifacts(delegate.artifacts))

    override fun getVariants() = delegate.variants.mapTo(mutableSetOf()) {
        DefaultVariantMetadata(it.name, it.identifier, asDescribable(), attributes, getArtifacts(), it.capabilities)
    }

    override fun artifact(artifact: IvyArtifactName) = artifact(delegate.artifact(artifact))
}
