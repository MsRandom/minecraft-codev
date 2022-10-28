package net.msrandom.minecraftcodev.gradle

import org.gradle.api.internal.attributes.ImmutableAttributes
import org.gradle.internal.DisplayName
import org.gradle.internal.component.model.*
import org.gradle.internal.impldep.com.google.common.collect.ImmutableList
import javax.inject.Inject

internal open class DelegatingConfigurationMetadata @Inject constructor(
    private val delegate: ConfigurationMetadata,
    private val describable: (DisplayName) -> DisplayName,
    private val attributes: ImmutableAttributes,
    private val dependency: (DependencyMetadata) -> DependencyMetadata,
    private val artifact: (ComponentArtifactMetadata) -> ComponentArtifactMetadata
) : ConfigurationMetadata by delegate {
    override fun asDescribable() = describable(delegate.asDescribable())
    override fun getAttributes() = attributes
    override fun getDependencies() = delegate.dependencies.map(dependency)
    override fun getArtifacts(): ImmutableList<ComponentArtifactMetadata> = ImmutableList.copyOf(delegate.artifacts.map(artifact))

    override fun getVariants() = delegate.variants.mapTo(mutableSetOf()) {
        DefaultVariantMetadata(it.name, it.identifier, asDescribable(), attributes, artifacts, it.capabilities)
    }

    override fun artifact(artifact: IvyArtifactName) = this.artifact(delegate.artifact(artifact))
}
