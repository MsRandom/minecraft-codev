package net.msrandom.minecraftcodev.gradle

import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.internal.component.model.ComponentResolveMetadata
import org.gradle.internal.component.model.ConfigurationMetadata
import org.gradle.internal.component.model.ModuleSources
import org.gradle.internal.impldep.com.google.common.base.Optional
import org.gradle.internal.impldep.com.google.common.collect.ImmutableList
import javax.inject.Inject

internal open class DelegatingComponentResolveMetadata @Inject constructor(
    @Suppress("MemberVisibilityCanBePrivate")
    val delegate: ComponentResolveMetadata,
    private val id: ComponentIdentifier,
    private val configuration: ConfigurationMetadata.() -> ConfigurationMetadata
) : ComponentResolveMetadata by delegate {
    override fun getId() = id
    override fun withSources(sources: ModuleSources) = DelegatingComponentResolveMetadata(delegate.withSources(sources), id, configuration)
    override fun getConfiguration(name: String) = delegate.getConfiguration(name)?.let(configuration)

    override fun getVariantsForGraphTraversal(): Optional<ImmutableList<out ConfigurationMetadata>> = delegate.variantsForGraphTraversal.let {
        if (it.isPresent) {
            val builder = ImmutableList.builder<ConfigurationMetadata>()

            for (variant in it.get()) {
                builder.add(variant.configuration())
            }

            Optional.of(builder.build())
        } else {
            Optional.absent()
        }
    }
}
