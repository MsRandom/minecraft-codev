package net.msrandom.minecraftcodev.gradle

import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.internal.component.external.model.ModuleComponentArtifactMetadata
import org.gradle.internal.component.model.ComponentResolveMetadata
import org.gradle.internal.component.model.ConfigurationMetadata
import org.gradle.internal.component.model.ModuleSources
import org.gradle.internal.impldep.com.google.common.base.Optional
import org.gradle.internal.impldep.com.google.common.collect.ImmutableList
import javax.inject.Inject

@Suppress("unused")
internal open class DelegatingComponentResolveMetadata @Inject constructor(
    @Suppress("MemberVisibilityCanBePrivate")
    val delegate: ComponentResolveMetadata,
    private val id: ModuleComponentIdentifier,
    private val configurations: (List<ConfigurationMetadata>) -> List<ConfigurationMetadata>,
    private val artifacts: (List<ModuleComponentArtifactMetadata>) -> List<ModuleComponentArtifactMetadata>,
    private val configuration: (ConfigurationMetadata) -> ConfigurationMetadata,
) : ComponentResolveMetadata by delegate {
    override fun getId() = id
    override fun withSources(sources: ModuleSources) = DelegatingComponentResolveMetadata(delegate.withSources(sources), id, configurations, artifacts, configuration)
    override fun getConfiguration(name: String) = delegate.getConfiguration(name)?.let(configuration)

    override fun getVariantsForGraphTraversal(): Optional<ImmutableList<out ConfigurationMetadata>> = delegate.variantsForGraphTraversal.let {
        it.transform {
            ImmutableList.builder<ConfigurationMetadata>().apply {
                configurations(it!!).forEach(::add)
            }.build()
        }
    }
}
