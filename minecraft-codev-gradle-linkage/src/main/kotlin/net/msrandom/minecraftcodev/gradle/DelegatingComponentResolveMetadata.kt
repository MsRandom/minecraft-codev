package net.msrandom.minecraftcodev.gradle

import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.internal.component.external.model.DefaultModuleComponentArtifactMetadata
import org.gradle.internal.component.external.model.ModuleComponentArtifactMetadata
import org.gradle.internal.component.model.ComponentResolveMetadata
import org.gradle.internal.component.model.ConfigurationMetadata
import org.gradle.internal.component.model.DefaultIvyArtifactName
import org.gradle.internal.component.model.ModuleSources
import org.gradle.internal.impldep.com.google.common.base.Optional
import org.gradle.internal.impldep.com.google.common.collect.ImmutableList
import javax.inject.Inject

internal open class DelegatingComponentResolveMetadata @Inject constructor(
    @Suppress("MemberVisibilityCanBePrivate")
    val delegate: ComponentResolveMetadata,
    private val id: ModuleComponentIdentifier,
    private val configuration: ConfigurationMetadata.() -> ConfigurationMetadata,
    private val artifact: (ModuleComponentArtifactMetadata) -> ModuleComponentArtifactMetadata
) : ComponentResolveMetadata by delegate, DefaultArtifactProvider {
    override val defaultArtifact = if (delegate is DefaultArtifactProvider) {
        artifact(delegate.defaultArtifact)
    } else {
        // Should be a pretty cold branch, as usually the cases above would be true if this is being called
        //  Only reason it's actually here is to allow this to not be nullable
        artifact(DefaultModuleComponentArtifactMetadata(id, DefaultIvyArtifactName(id.module, ArtifactTypeDefinition.JAR_TYPE, ArtifactTypeDefinition.JAR_TYPE)))
    }

    override fun getId() = id
    override fun withSources(sources: ModuleSources) = DelegatingComponentResolveMetadata(delegate.withSources(sources), id, configuration, artifact)
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
