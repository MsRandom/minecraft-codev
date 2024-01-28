package net.msrandom.minecraftcodev.core

import net.msrandom.minecraftcodev.core.dependency.resolverFactories
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.query.ArtifactResolutionQuery
import org.gradle.api.artifacts.result.ArtifactResolutionResult
import org.gradle.api.artifacts.result.ComponentArtifactsResult
import org.gradle.api.artifacts.result.ComponentResult
import org.gradle.api.component.Artifact
import org.gradle.api.component.Component
import org.gradle.api.internal.artifacts.DefaultModuleIdentifier
import org.gradle.api.internal.artifacts.GlobalDependencyResolutionRules
import org.gradle.api.internal.artifacts.RepositoriesSupplier
import org.gradle.api.internal.artifacts.configurations.ConfigurationContainerInternal
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.ComponentResolvers
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.ErrorHandlingArtifactResolver
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.ResolveIvyFactory
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.ComponentResolversChain
import org.gradle.api.internal.artifacts.result.*
import org.gradle.api.internal.artifacts.type.ArtifactTypeRegistry
import org.gradle.api.internal.attributes.ImmutableAttributes
import org.gradle.api.internal.attributes.ImmutableAttributesFactory
import org.gradle.api.internal.component.ComponentTypeRegistry
import org.gradle.api.invocation.Gradle
import org.gradle.api.model.ObjectFactory
import org.gradle.internal.Describables
import org.gradle.internal.component.external.model.DefaultModuleComponentIdentifier
import org.gradle.internal.component.model.ComponentArtifactResolveState
import org.gradle.internal.component.model.DefaultComponentOverrideMetadata
import org.gradle.internal.model.CalculatedValueContainerFactory
import org.gradle.internal.resolve.caching.ComponentMetadataSupplierRuleExecutor
import org.gradle.internal.resolve.resolver.ArtifactResolver
import org.gradle.internal.resolve.resolver.ComponentMetaDataResolver
import org.gradle.internal.resolve.result.DefaultBuildableArtifactResolveResult
import org.gradle.internal.resolve.result.DefaultBuildableArtifactSetResolveResult
import org.gradle.internal.resolve.result.DefaultBuildableComponentResolveResult
import javax.inject.Inject

open class CodevArtifactResolutionQuery @Inject constructor(
    private val configurationContainer: ConfigurationContainerInternal,
    private val repositoriesSupplier: RepositoriesSupplier,
    private val ivyFactory: ResolveIvyFactory,
    private val metadataHandler: GlobalDependencyResolutionRules,
    private val componentTypeRegistry: ComponentTypeRegistry,
    private val attributesFactory: ImmutableAttributesFactory,
    private val componentMetadataSupplierRuleExecutor: ComponentMetadataSupplierRuleExecutor,
    private val objectFactory: ObjectFactory,
    private val gradle: Gradle,
) : ArtifactResolutionQuery {
    private val componentIds = mutableSetOf<ComponentIdentifier>()
    private var componentType: Class<out Component>? = null
    private val artifactTypes = mutableSetOf<Class<out Artifact>>()

    override fun forComponents(componentIds: Iterable<ComponentIdentifier>): ArtifactResolutionQuery = apply {
        this.componentIds.addAll(componentIds)
    }

    override fun forComponents(vararg componentIds: ComponentIdentifier) = forComponents(listOf(*componentIds))

    override fun forModule(group: String, name: String, version: String): ArtifactResolutionQuery = apply {
        componentIds.add(DefaultModuleComponentIdentifier.newId(DefaultModuleIdentifier.newId(group, name), version))
    }

    override fun withArtifacts(componentType: Class<out Component>, vararg artifactTypes: Class<out Artifact>) = withArtifacts(componentType, listOf(*artifactTypes))

    override fun withArtifacts(componentType: Class<out Component>, artifactTypes: Collection<Class<out Artifact>>): ArtifactResolutionQuery {
        check(this.componentType == null) { "Cannot specify component type multiple times." }
        this.componentType = componentType
        this.artifactTypes.addAll(artifactTypes)
        return this
    }

    override fun execute(): ArtifactResolutionResult {
        checkNotNull(componentType) { "Must specify component type and artifacts to query." }
        val repositories = repositoriesSupplier.get()
        val detachedConfiguration = configurationContainer.detachedConfiguration()
        val resolutionStrategy = detachedConfiguration.resolutionStrategy
        val resolvers = mutableListOf<ComponentResolvers>()

        for (resolverFactory in gradle.resolverFactories) {
            resolverFactory.create(detachedConfiguration, resolvers)
        }

        resolvers.add(ivyFactory.create(
            detachedConfiguration.name,
            resolutionStrategy,
            repositories,
            metadataHandler.componentMetadataProcessorFactory,
            ImmutableAttributes.EMPTY,
            null,
            attributesFactory,
            componentMetadataSupplierRuleExecutor
        ))

        val componentResolvers =  objectFactory.newInstance(ComponentResolversChain::class.java, resolvers)

        val componentMetaDataResolver = componentResolvers.componentResolver
        val artifactResolver = ErrorHandlingArtifactResolver(componentResolvers.artifactResolver)

        return createResult(componentMetaDataResolver, artifactResolver)
    }

    private fun createResult(componentMetaDataResolver: ComponentMetaDataResolver, artifactResolver: ArtifactResolver): ArtifactResolutionResult {
        val componentResults = hashSetOf<ComponentResult>()
        for (componentId in componentIds) {
            try {
                componentResults.add(buildComponentResult(componentId, componentMetaDataResolver, artifactResolver))
            } catch (t: Exception) {
                componentResults.add(DefaultUnresolvedComponentResult(componentId, t))
            }
        }
        return DefaultArtifactResolutionResult(componentResults)
    }

    private fun buildComponentResult(componentId: ComponentIdentifier, componentMetaDataResolver: ComponentMetaDataResolver, artifactResolver: ArtifactResolver): ComponentArtifactsResult {
        val moduleResolveResult = DefaultBuildableComponentResolveResult()
        componentMetaDataResolver.resolve(componentId, DefaultComponentOverrideMetadata.EMPTY, moduleResolveResult)
        val component = moduleResolveResult.state.prepareForArtifactResolution()
        val componentResult = DefaultComponentArtifactsResult(component.id)
        for (artifactType in artifactTypes) {
            addArtifacts(componentResult, artifactType, component, artifactResolver)
        }
        return componentResult
    }

    private fun <T : Artifact> addArtifacts(artifacts: DefaultComponentArtifactsResult, type: Class<T>, component: ComponentArtifactResolveState, artifactResolver: ArtifactResolver) {
        val artifactSetResolveResult = DefaultBuildableArtifactSetResolveResult()
        component.resolveArtifactsWithType(artifactResolver, convertType(type), artifactSetResolveResult)
        for (artifactMetaData in artifactSetResolveResult.result) {
            val resolveResult = DefaultBuildableArtifactResolveResult()
            artifactResolver.resolveArtifact(component.resolveMetadata, artifactMetaData, resolveResult)
            if (resolveResult.failure != null) {
                artifacts.addArtifact(DefaultUnresolvedArtifactResult(artifactMetaData.id, type, resolveResult.failure))
            } else {
                artifacts.addArtifact(
                    ivyFactory.verifiedArtifact(
                        DefaultResolvedArtifactResult(
                            artifactMetaData.id,
                            ImmutableAttributes.EMPTY,
                            emptyList(),
                            Describables.of(component.id.displayName),
                            type,
                            resolveResult.result.file
                        )
                    )
                )
            }
        }
    }

    private fun <T : Artifact> convertType(requestedType: Class<T>) =
        componentTypeRegistry.getComponentRegistration(componentType).getArtifactType(requestedType)
}
