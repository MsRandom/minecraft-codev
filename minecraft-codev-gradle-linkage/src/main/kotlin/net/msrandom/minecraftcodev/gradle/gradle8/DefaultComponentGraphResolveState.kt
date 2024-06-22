
package net.msrandom.minecraftcodev.gradle.gradle8

import org.gradle.api.artifacts.ModuleVersionIdentifier
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.ArtifactSet
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.excludes.specs.ExcludeSpec
import org.gradle.api.internal.attributes.AttributesSchemaInternal
import org.gradle.api.internal.attributes.ImmutableAttributes
import org.gradle.internal.component.external.model.ModuleComponentResolveMetadata
import org.gradle.internal.component.model.*
import org.gradle.internal.impldep.com.google.common.base.Optional
import org.gradle.internal.resolve.resolver.ArtifactSelector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.stream.Collectors
import javax.inject.Inject

@Suppress("unused")
open class DefaultComponentGraphResolveState<T : ComponentGraphResolveMetadata, S : ComponentResolveMetadata> @Inject
constructor(
    graphMetadata: T,
    artifactMetadata: S,
) :
    AbstractComponentGraphResolveState<T, S>(graphMetadata, artifactMetadata) {
    private val variants: ConcurrentMap<ConfigurationMetadata, DefaultVariantArtifactResolveState> = ConcurrentHashMap()

    override fun getResolveMetadata(): ComponentArtifactResolveMetadata {
        return ExternalArtifactResolveMetadata(artifactMetadata)
    }

    override fun resolveArtifactsFor(variant: VariantGraphResolveMetadata): VariantArtifactGraphResolveMetadata {
        return variant as VariantArtifactGraphResolveMetadata
    }

    override fun prepareForArtifactResolution(variant: VariantGraphResolveMetadata): VariantArtifactResolveState {
        val configurationMetadata = variant as ConfigurationMetadata
        return variants.computeIfAbsent(configurationMetadata) { c: ConfigurationMetadata? ->
            DefaultVariantArtifactResolveState(
                metadata,
                artifactMetadata,
                configurationMetadata,
            )
        }
    }

    private class DefaultVariantArtifactResolveState(
        graphMetadata: ComponentGraphResolveMetadata,
        private val artifactMetadata: ComponentResolveMetadata,
        private val graphSelectedVariant: ConfigurationMetadata,
    ) : VariantArtifactResolveState {
        private val fallbackVariants: Set<VariantResolveMetadata?> = graphSelectedVariant.variants
        private val allVariants: Set<VariantResolveMetadata?>

        init {
            val variantsForGraphTraversal = graphMetadata.variantsForGraphTraversal
            allVariants = buildAllVariants(fallbackVariants, variantsForGraphTraversal)
        }

        override fun resolveArtifact(artifact: IvyArtifactName): ComponentArtifactMetadata {
            return graphSelectedVariant.artifact(artifact)
        }

        override fun resolveArtifacts(
            artifactSelector: ArtifactSelector,
            exclusions: ExcludeSpec,
            overriddenAttributes: ImmutableAttributes,
        ): ArtifactSet {
            return artifactSelector.resolveArtifacts(ExternalArtifactResolveMetadata(artifactMetadata), {
                allVariants
            }, fallbackVariants, exclusions, overriddenAttributes)
        }

        companion object {
            private fun buildAllVariants(
                fallbackVariants: Set<VariantResolveMetadata?>,
                variantsForGraphTraversal: Optional<List<VariantGraphResolveMetadata>>,
            ): Set<VariantResolveMetadata?> {
                val allVariants =
                    if (variantsForGraphTraversal.isPresent) {
                        variantsForGraphTraversal.get().stream().map { obj: Any? -> ConfigurationMetadata::class.java.cast(obj) }
                            .flatMap { variant: ConfigurationMetadata -> variant.variants.stream() }.collect(Collectors.toSet())
                    } else {
                        fallbackVariants
                    }
                return allVariants
            }
        }
    }

    private class ExternalArtifactResolveMetadata(private val metadata: ComponentResolveMetadata) : ComponentArtifactResolveMetadata {
        override fun getId(): ComponentIdentifier {
            return metadata.id
        }

        override fun getModuleVersionId(): ModuleVersionIdentifier {
            return metadata.moduleVersionId
        }

        override fun getSources(): ModuleSources? {
            return metadata.sources
        }

        override fun getAttributes(): ImmutableAttributes {
            return metadata.attributes
        }

        override fun getAttributesSchema(): AttributesSchemaInternal {
            return metadata.attributesSchema!!
        }

        override fun getMetadata(): ComponentResolveMetadata {
            return metadata
        }
    }

    companion object {
        fun of(metadata: ModuleComponentResolveMetadata): ComponentGraphResolveState {
            return DefaultComponentGraphResolveState(metadata, metadata)
        }
    }
}
