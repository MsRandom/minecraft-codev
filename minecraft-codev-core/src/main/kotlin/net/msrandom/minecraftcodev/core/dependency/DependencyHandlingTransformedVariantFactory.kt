package net.msrandom.minecraftcodev.core.dependency

import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.ResolvedArtifactSet
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.ResolvedVariant
import org.gradle.api.internal.artifacts.transform.*
import org.gradle.api.internal.attributes.ImmutableAttributes
import org.gradle.api.internal.tasks.TaskDependencyResolveContext
import org.gradle.internal.component.model.VariantResolveMetadata
import org.gradle.internal.model.CalculatedValueContainerFactory
import org.gradle.internal.operations.BuildOperationExecutor
import java.util.concurrent.ConcurrentHashMap
import javax.annotation.concurrent.ThreadSafe
import javax.inject.Inject

@ThreadSafe
open class DependencyHandlingTransformedVariantFactory @Inject constructor(buildOperationExecutor: BuildOperationExecutor, private val calculatedValueContainerFactory: CalculatedValueContainerFactory) :
    TransformedVariantFactory {
    private val transformationNodeFactory = DefaultTransformationNodeFactory(buildOperationExecutor, calculatedValueContainerFactory)
    private val variants = ConcurrentHashMap<VariantKey, ResolvedArtifactSet>()
    private val externalFactory = ::doCreateExternal
    private val projectFactory = ::doCreateProject

    override fun transformedExternalArtifacts(
        componentIdentifier: ComponentIdentifier,
        sourceVariant: ResolvedVariant,
        variantDefinition: VariantDefinition,
        dependenciesResolverFactory: ExtraExecutionGraphDependenciesResolverFactory
    ) = locateOrCreate(externalFactory, componentIdentifier, sourceVariant, variantDefinition, dependenciesResolverFactory)

    override fun transformedProjectArtifacts(
        componentIdentifier: ComponentIdentifier,
        sourceVariant: ResolvedVariant,
        variantDefinition: VariantDefinition,
        dependenciesResolverFactory: ExtraExecutionGraphDependenciesResolverFactory
    ) = locateOrCreate(projectFactory, componentIdentifier, sourceVariant, variantDefinition, dependenciesResolverFactory)

    private fun locateOrCreate(
        factory: (
            componentIdentifier: ComponentIdentifier,
            sourceVariant: ResolvedVariant,
            variantDefinition: VariantDefinition,
            dependenciesResolverFactory: ExtraExecutionGraphDependenciesResolverFactory
        ) -> ResolvedArtifactSet,
        componentIdentifier: ComponentIdentifier,
        sourceVariant: ResolvedVariant,
        variantDefinition: VariantDefinition,
        dependenciesResolverFactory: ExtraExecutionGraphDependenciesResolverFactory
    ): ResolvedArtifactSet {
        val target = variantDefinition.targetAttributes
        val transformation = variantDefinition.transformation
        val identifier = sourceVariant.identifier ?: return factory(componentIdentifier, sourceVariant, variantDefinition, dependenciesResolverFactory) // An ad hoc variant, do not cache the result

        val variantKey = if (transformation.requiresDependencies()) {
            VariantWithUpstreamDependenciesKey(identifier, target, dependenciesResolverFactory)
        } else {
            VariantKey(identifier, target)
        }

        // Can't use computeIfAbsent() as the default implementation does not allow recursive updates
        var result = variants[variantKey]
        if (result == null) {
            val newResult = factory(componentIdentifier, sourceVariant, variantDefinition, dependenciesResolverFactory)
            result = variants.putIfAbsent(variantKey, newResult)
            if (result == null) {
                result = newResult
            }
        }
        return result
    }

    private fun doCreateExternal(
        componentIdentifier: ComponentIdentifier,
        sourceVariant: ResolvedVariant,
        variantDefinition: VariantDefinition,
        dependenciesResolverFactory: ExtraExecutionGraphDependenciesResolverFactory
    ): TransformedExternalArtifactSet {
        return object : TransformedExternalArtifactSet(
            componentIdentifier,
            sourceVariant.artifacts,
            variantDefinition.targetAttributes,
            sourceVariant.capabilities.capabilities,
            variantDefinition.transformation,
            dependenciesResolverFactory,
            calculatedValueContainerFactory
        ) {
            override fun visitDependencies(context: TaskDependencyResolveContext) {
                sourceVariant.artifacts.visitDependencies(context)
            }
        }
    }

    private fun doCreateProject(
        componentIdentifier: ComponentIdentifier,
        sourceVariant: ResolvedVariant,
        variantDefinition: VariantDefinition,
        dependenciesResolverFactory: ExtraExecutionGraphDependenciesResolverFactory
    ): TransformedProjectArtifactSet {
        val sourceArtifacts = if (variantDefinition.sourceVariant != null) {
            transformedProjectArtifacts(componentIdentifier, sourceVariant, variantDefinition.sourceVariant!!, dependenciesResolverFactory)
        } else {
            sourceVariant.artifacts
        }

        return TransformedProjectArtifactSet(componentIdentifier, sourceArtifacts, variantDefinition, sourceVariant.capabilities.capabilities, dependenciesResolverFactory, transformationNodeFactory)
    }

    private open class VariantKey(private val sourceVariant: VariantResolveMetadata.Identifier, private val target: ImmutableAttributes) {
        override fun hashCode(): Int {
            return sourceVariant.hashCode() xor target.hashCode()
        }

        override fun equals(other: Any?): Boolean {
            if (other === this) {
                return true
            }

            if (other == null || other.javaClass != javaClass) {
                return false
            }

            other as VariantKey
            return sourceVariant == other.sourceVariant && target == other.target
        }
    }

    private class VariantWithUpstreamDependenciesKey(
        sourceVariant: VariantResolveMetadata.Identifier,
        target: ImmutableAttributes,
        private val dependencies: ExtraExecutionGraphDependenciesResolverFactory
    ) : VariantKey(sourceVariant, target) {
        override fun hashCode() = super.hashCode() xor dependencies.hashCode()

        override fun equals(other: Any?): Boolean {
            if (!super.equals(other)) {
                return false
            }

            other as VariantWithUpstreamDependenciesKey
            return dependencies === other.dependencies
        }
    }
}
