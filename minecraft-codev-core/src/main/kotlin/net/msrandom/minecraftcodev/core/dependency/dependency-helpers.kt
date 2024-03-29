package net.msrandom.minecraftcodev.core.dependency

import net.msrandom.minecraftcodev.core.CodevArtifactResolutionQuery
import net.msrandom.minecraftcodev.core.caches.CodevCacheProvider
import net.msrandom.minecraftcodev.core.resolve.ComponentResolversChainProvider
import net.msrandom.minecraftcodev.core.resolve.IvyDependencyDescriptorFactoriesProvider
import net.msrandom.minecraftcodev.core.utils.getCacheProvider
import org.gradle.api.Project
import org.gradle.api.internal.DomainObjectContext
import org.gradle.api.internal.GradleInternal
import org.gradle.api.internal.artifacts.ArtifactDependencyResolver
import org.gradle.api.internal.artifacts.configurations.ConfigurationInternal
import org.gradle.api.internal.artifacts.configurations.DefaultConfiguration
import org.gradle.api.internal.artifacts.configurations.dynamicversion.CachePolicy
import org.gradle.api.internal.artifacts.dsl.dependencies.DefaultDependencyHandler
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.ComponentResolvers
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.ResolverProviderFactory
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.StartParameterResolutionOverride
import org.gradle.api.internal.artifacts.ivyservice.moduleconverter.dependencies.DefaultDependencyMetadataFactory
import org.gradle.api.internal.artifacts.ivyservice.moduleconverter.dependencies.DependencyMetadataFactory
import org.gradle.api.internal.artifacts.ivyservice.moduleconverter.dependencies.DependencyMetadataConverter
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.ComponentResolversChain
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.DefaultArtifactDependencyResolver
import org.gradle.api.internal.artifacts.query.ArtifactResolutionQueryFactory
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
import org.gradle.internal.instantiation.InstantiatorFactory
import org.gradle.internal.reflect.Instantiator
import org.gradle.internal.service.DefaultServiceRegistry
import sun.misc.Unsafe

private val customDependencies = hashMapOf<Gradle, DependencyData>()

private val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }[null] as Unsafe

private val dependencyDescriptorFactoriesField = DefaultDependencyMetadataFactory::class.java.getDeclaredField("dependencyDescriptorFactories").apply { isAccessible = true }
private val resolverFactoriesField = DefaultArtifactDependencyResolver::class.java.getDeclaredField("resolverFactories").apply { isAccessible = true }
private val domainObjectContextField = DefaultConfiguration::class.java.getDeclaredField("domainObjectContext").apply { isAccessible = true }

private val resolutionQueryFactoryOffset = unsafe.objectFieldOffset(DefaultDependencyHandler::class.java.getDeclaredField("resolutionQueryFactory"))

val Gradle.resolverFactories: List<ResolverProviderFactory>
    get() {
        val resolvers = (gradle as GradleInternal).services.getAll(ResolverProviderFactory::class.java)

        customDependencies[gradle]?.componentResolvers?.forEach {
            addResolvers(resolvers, it, getDependencyDescriptorFactories())
        }

        return resolvers
    }


@Suppress("UNCHECKED_CAST")
private fun addResolvers(
    resolverProviderFactories: MutableList<ResolverProviderFactory>,
    componentResolvers: Class<out ComponentResolvers>,
    ivyDependencyDescriptorFactories: List<DependencyMetadataConverter>
) {
    resolverProviderFactories.add(ResolverProviderFactory { context, resolvers ->
        val configuration = context as? DefaultConfiguration
        val project = configuration?.let { (domainObjectContextField[it] as DomainObjectContext).project }

        if (project != null) {
            val instantiatorFactory = project.serviceOf<InstantiatorFactory>()
            val startParameterResolutionOverride = project.serviceOf<StartParameterResolutionOverride>()

            val newServices = DefaultServiceRegistry(project.services)

            val cachePolicy = context.resolutionStrategy.cachePolicy

            startParameterResolutionOverride.applyToCachePolicy(cachePolicy)

            val resolversProvider by lazy {
                ComponentResolversChain(resolvers as List<ComponentResolvers>, project.serviceOf(), project.serviceOf(), project.serviceOf())
            }

            newServices.add(ConfigurationInternal::class.java, configuration)
            newServices.add(CachePolicy::class.java, cachePolicy)
            newServices.add(CodevCacheProvider::class.java, getCacheProvider(project.gradle))
            newServices.add(ComponentResolversChainProvider::class.java, ComponentResolversChainProvider { resolversProvider })
            newServices.add(IvyDependencyDescriptorFactoriesProvider::class.java, IvyDependencyDescriptorFactoriesProvider { ivyDependencyDescriptorFactories })

            val gradle = project.gradle as GradleInternal

            val projectObjectFactory = DefaultObjectFactory(
                instantiatorFactory.decorate(newServices),

                // Gradle doesn't allow using the object factory to create an object factory so no automatic dependency injection, so
                // instead we just use inference to inject everything :)
                gradle.serviceOf(),
                gradle.serviceOf(),
                gradle.serviceOf(),
                gradle.serviceOf(),
                gradle.serviceOf(),
                gradle.serviceOf(),
                gradle.serviceOf(),
                gradle.serviceOf(),
            )

            newServices.add(ObjectFactory::class.java, projectObjectFactory)

            resolvers.add(projectObjectFactory.newInstance(componentResolvers))
        }
    })
}

// Patching artifact resolution queries to allow sources/javadoc artifact types from custom resolvers
fun Project.handleCustomQueryResolvers() {
    unsafe.putObject(dependencies, resolutionQueryFactoryOffset, ArtifactResolutionQueryFactory {
        objects.newInstance(CodevArtifactResolutionQuery::class.java)
    })
}

fun Gradle.getDependencyDescriptorFactories(): MutableList<DependencyMetadataConverter> {
    this as GradleInternal

    val dependencyDescriptorFactory = serviceOf<DependencyMetadataFactory>()

    @Suppress("UNCHECKED_CAST")
    return dependencyDescriptorFactoriesField[dependencyDescriptorFactory] as MutableList<DependencyMetadataConverter>
}

@Suppress("UNCHECKED_CAST")
fun Gradle.registerCustomDependency(
    name: String,
    descriptorFactory: Class<out DependencyMetadataConverter>,
    componentResolvers: Class<out ComponentResolvers>
) {
    val dependency = customDependencies.computeIfAbsent(gradle) { DependencyData() }

    if (name !in dependency.registeredTypeNames) {
        val gradle = gradle
        gradle as GradleInternal
        val artifactDependencyResolver = gradle.serviceOf<ArtifactDependencyResolver>()
        val objectFactory = gradle.serviceOf<ObjectFactory>()

        val ivyDependencyDescriptorFactories = gradle.getDependencyDescriptorFactories()
        val resolverProviderFactories = resolverFactoriesField[artifactDependencyResolver] as MutableList<ResolverProviderFactory>

        ivyDependencyDescriptorFactories.add(objectFactory.newInstance(descriptorFactory))

        addResolvers(resolverProviderFactories, componentResolvers, ivyDependencyDescriptorFactories)

        dependency.componentResolvers.add(componentResolvers)
        dependency.registeredTypeNames.add(name)
    }
}

private class DependencyData(
    val registeredTypeNames: MutableSet<String> = hashSetOf(),
    val componentResolvers: MutableList<Class<out ComponentResolvers>> = mutableListOf()
)
