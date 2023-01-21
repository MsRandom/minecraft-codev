package net.msrandom.minecraftcodev.core.dependency

import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin
import net.msrandom.minecraftcodev.core.caches.CodevCacheProvider
import net.msrandom.minecraftcodev.core.resolve.ComponentResolversChainProvider
import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.internal.DomainObjectContext
import org.gradle.api.internal.GradleInternal
import org.gradle.api.internal.artifacts.ArtifactDependencyResolver
import org.gradle.api.internal.artifacts.configurations.ConfigurationInternal
import org.gradle.api.internal.artifacts.configurations.DefaultConfiguration
import org.gradle.api.internal.artifacts.configurations.dynamicversion.CachePolicy
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.ComponentResolvers
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.ResolverProviderFactory
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.StartParameterResolutionOverride
import org.gradle.api.internal.artifacts.ivyservice.moduleconverter.dependencies.DefaultDependencyDescriptorFactory
import org.gradle.api.internal.artifacts.ivyservice.moduleconverter.dependencies.DependencyDescriptorFactory
import org.gradle.api.internal.artifacts.ivyservice.moduleconverter.dependencies.IvyDependencyDescriptorFactory
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.ComponentResolversChain
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.DefaultArtifactDependencyResolver
import org.gradle.api.internal.collections.DomainObjectCollectionFactory
import org.gradle.api.internal.file.FileCollectionFactory
import org.gradle.api.internal.file.FilePropertyFactory
import org.gradle.api.internal.file.collections.DirectoryFileTreeFactory
import org.gradle.api.internal.model.DefaultObjectFactory
import org.gradle.api.internal.model.NamedObjectInstantiator
import org.gradle.api.internal.provider.PropertyFactory
import org.gradle.api.invocation.Gradle
import org.gradle.api.model.ObjectFactory
import org.gradle.api.tasks.util.PatternSet
import org.gradle.configurationcache.extensions.serviceOf
import org.gradle.internal.Factory
import org.gradle.internal.component.local.model.DslOriginDependencyMetadata
import org.gradle.internal.component.model.DependencyMetadata
import org.gradle.internal.instantiation.InstantiatorFactory
import org.gradle.internal.service.DefaultServiceRegistry

private val customDependencies = hashMapOf<Gradle, DependencyData>()

private val dependencyDescriptorFactoriesField = DefaultDependencyDescriptorFactory::class.java.getDeclaredField("dependencyDescriptorFactories").apply {
    isAccessible = true
}

private val resolverFactoriesField = DefaultArtifactDependencyResolver::class.java.getDeclaredField("resolverFactories").apply {
    isAccessible = true
}

private val domainObjectContextField = DefaultConfiguration::class.java.getDeclaredField("domainObjectContext").apply {
    isAccessible = true
}

val Gradle.resolverFactories: List<ResolverProviderFactory>
    get() {
        val resolvers = (gradle as GradleInternal).services.getAll(ResolverProviderFactory::class.java)

        customDependencies[gradle]?.componentResolvers?.forEach {
            addResolvers(resolvers, it)
        }

        return resolvers
    }


@Suppress("UNCHECKED_CAST")
private fun addResolvers(resolverProviderFactories: MutableList<ResolverProviderFactory>, componentResolvers: Class<out ComponentResolvers>) {
    resolverProviderFactories.add(ResolverProviderFactory { context, resolvers ->
        val configuration = context as? DefaultConfiguration
        val project = configuration?.let { (domainObjectContextField[it] as DomainObjectContext).project }

        if (project != null) {
            val instantiatorFactory = project.serviceOf<InstantiatorFactory>()
            val directoryFileTreeFactory = project.serviceOf<DirectoryFileTreeFactory>()
            val patternSetFactory = project.gradle.serviceOf<Factory<PatternSet>>()
            val propertyFactory = project.serviceOf<PropertyFactory>()
            val filePropertyFactory = project.serviceOf<FilePropertyFactory>()
            val fileCollectionFactory = project.serviceOf<FileCollectionFactory>()
            val domainObjectCollectionFactory = project.serviceOf<DomainObjectCollectionFactory>()
            val instantiator = project.serviceOf<NamedObjectInstantiator>()
            val startParameterResolutionOverride = project.serviceOf<StartParameterResolutionOverride>()

            val newServices = DefaultServiceRegistry(project.services)

            val cachePolicy = context.resolutionStrategy.cachePolicy

            startParameterResolutionOverride.applyToCachePolicy(cachePolicy)

            val resolversProvider by lazy {
                ComponentResolversChain(resolvers as List<ComponentResolvers>, project.serviceOf(), project.serviceOf())
            }

            newServices.add(ConfigurationInternal::class.java, configuration)
            newServices.add(CachePolicy::class.java, cachePolicy)
            newServices.add(CodevCacheProvider::class.java, MinecraftCodevPlugin.getCacheProvider(project.gradle))
            newServices.add(ComponentResolversChainProvider::class.java, ComponentResolversChainProvider { resolversProvider })

            val projectObjectFactory = DefaultObjectFactory(
                instantiatorFactory.decorate(newServices),
                instantiator,
                directoryFileTreeFactory,
                patternSetFactory,
                propertyFactory,
                filePropertyFactory,
                fileCollectionFactory,
                domainObjectCollectionFactory
            )

            newServices.add(ObjectFactory::class.java, projectObjectFactory)

            resolvers.add(projectObjectFactory.newInstance(componentResolvers))
        }
    })
}

@Suppress("UNCHECKED_CAST")
fun Gradle.registerCustomDependency(
    name: String,
    descriptorFactory: Class<out IvyDependencyDescriptorFactory>,
    dependencyFactory: Class<out DependencyFactory>,
    componentResolvers: Class<out ComponentResolvers>,
    edgeDependency: Class<out DependencyMetadata>? = null
) {
    val dependency = customDependencies.computeIfAbsent(gradle) { DependencyData() }

    if (name !in dependency.registeredTypeNames) {
        val gradle = gradle
        gradle as GradleInternal
        val dependencyDescriptorFactory = gradle.serviceOf<DependencyDescriptorFactory>()
        val artifactDependencyResolver = gradle.serviceOf<ArtifactDependencyResolver>()
        val objectFactory = gradle.serviceOf<ObjectFactory>()

        val ivyDependencyDescriptorFactories = dependencyDescriptorFactoriesField[dependencyDescriptorFactory] as MutableList<IvyDependencyDescriptorFactory>
        val resolverProviderFactories = resolverFactoriesField[artifactDependencyResolver] as MutableList<ResolverProviderFactory>

        ivyDependencyDescriptorFactories.add(objectFactory.newInstance(descriptorFactory))

        addResolvers(resolverProviderFactories, componentResolvers)
        dependency.componentResolvers.add(componentResolvers)

        dependency.dependencyFactories.add(objectFactory.newInstance(dependencyFactory))
        dependency.registeredTypeNames.add(name)
        edgeDependency?.let(dependency.edgeTypes::add)
    }
}

fun Project.convertDescriptor(descriptor: DependencyMetadata): Dependency {
    if (descriptor is DslOriginDependencyMetadata) {
        return descriptor.source
    }

    for (factory in customDependencies[gradle]?.dependencyFactories.orEmpty()) {
        if (factory.canConvert(descriptor)) {
            return factory.createDependency(project, descriptor)
        }
    }

    val selector = descriptor.selector

    if (selector is ModuleComponentSelector) {
        return (project.dependencies.create(selector.moduleIdentifier.toString()) as ExternalModuleDependency).apply {
            version { versionConstraint ->
                selector.versionConstraint.requiredVersion.takeUnless(String::isEmpty)?.let(versionConstraint::require)
                selector.versionConstraint.preferredVersion.takeUnless(String::isEmpty)?.let(versionConstraint::require)
                selector.versionConstraint.rejectedVersions.takeUnless(List<*>::isEmpty)?.let { versionConstraint.reject(*it.toTypedArray()) }
                selector.versionConstraint.strictVersion.takeUnless(String::isEmpty)?.let(versionConstraint::require)
            }

            for (artifact in descriptor.artifacts) {
                artifact {
                    it.name = artifact.name
                    it.type = artifact.type

                    artifact.extension?.let { extension -> it.extension = extension }
                    artifact.classifier?.let { classifier -> it.classifier = classifier }
                }
            }

        }
    }

    throw UnsupportedOperationException("Don't know how to convert dependency metadata $descriptor into a DSL dependency for resolution.")
}

private class DependencyData(
    val registeredTypeNames: MutableSet<String> = hashSetOf(),
    val dependencyFactories: MutableList<DependencyFactory> = mutableListOf(),
    val edgeTypes: MutableList<Class<out DependencyMetadata>> = mutableListOf(),
    val componentResolvers: MutableList<Class<out ComponentResolvers>> = mutableListOf()
)
