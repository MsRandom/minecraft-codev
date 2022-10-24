package net.msrandom.minecraftcodev.gradle

import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.internal.component.model.ComponentResolveMetadata
import org.gradle.internal.component.model.ConfigurationMetadata
import org.gradle.internal.component.model.ModuleSources
import org.gradle.internal.impldep.com.google.common.base.Optional
import org.gradle.internal.impldep.com.google.common.collect.ImmutableList
import java.util.function.Function
import javax.inject.Inject

internal open class DelegatingComponentResolveMetadata @Inject constructor(
    @Suppress("MemberVisibilityCanBePrivate")
    val delegate: ComponentResolveMetadata,
    private val id: ComponentIdentifier,
    private val variants: (Map<String, ConfigurationMetadata?>) -> Map<String, ConfigurationMetadata?>
) : ComponentResolveMetadata by delegate {
    private val configurations by lazy {
        variants(delegate.configurationNames.associateWith(::getConfiguration))
    }

    override fun getId() = id
    override fun withSources(sources: ModuleSources) = DelegatingComponentResolveMetadata(delegate.withSources(sources), id, variants)
    override fun getConfigurationNames() = configurations.keys
    override fun getConfiguration(name: String): ConfigurationMetadata? = configurations[name]

    override fun getVariantsForGraphTraversal(): Optional<ImmutableList<out ConfigurationMetadata>> = delegate.variantsForGraphTraversal.let {
        @Suppress("UNCHECKED_CAST")
        if (it.isPresent) Optional.of(ImmutableList.copyOf(variants(it.get().associateBy(ConfigurationMetadata::getName)).values) as ImmutableList<out ConfigurationMetadata>) else it
    }
}
