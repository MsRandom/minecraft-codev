package net.msrandom.minecraftcodev.core

import net.msrandom.minecraftcodev.core.caches.CodevCacheProvider
import net.msrandom.minecraftcodev.core.dependency.MinecraftDependencyExtension
import net.msrandom.minecraftcodev.core.dependency.MinecraftIvyDependencyDescriptorFactory
import net.msrandom.minecraftcodev.core.resolve.MinecraftComponentResolvers
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.attributes.Attribute
import org.gradle.api.initialization.Settings
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
import org.gradle.api.internal.artifacts.type.ArtifactTypeRegistry
import org.gradle.api.internal.attributes.ImmutableAttributesFactory
import org.gradle.api.internal.collections.DomainObjectCollectionFactory
import org.gradle.api.internal.file.FileCollectionFactory
import org.gradle.api.internal.file.FilePropertyFactory
import org.gradle.api.internal.file.collections.DirectoryFileTreeFactory
import org.gradle.api.internal.model.DefaultObjectFactory
import org.gradle.api.internal.model.NamedObjectInstantiator
import org.gradle.api.internal.provider.PropertyFactory
import org.gradle.api.invocation.Gradle
import org.gradle.api.model.ObjectFactory
import org.gradle.api.plugins.ApplicationPlugin
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.PluginAware
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.util.PatternSet
import org.gradle.configurationcache.extensions.capitalized
import org.gradle.configurationcache.extensions.serviceOf
import org.gradle.internal.Factory
import org.gradle.internal.instantiation.InstantiatorFactory
import org.gradle.internal.model.CalculatedValueContainerFactory
import org.gradle.internal.service.DefaultServiceRegistry
import org.gradle.internal.work.WorkerLeaseService
import java.net.URI
import java.nio.file.FileSystem
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import javax.inject.Inject
import kotlin.streams.asSequence

open class MinecraftCodevPlugin<T : PluginAware> @Inject constructor(private val attributesFactory: ImmutableAttributesFactory) : Plugin<T> {
    private fun applyGradle(gradle: Gradle) {
        registerCustomDependency("minecraft", gradle, MinecraftIvyDependencyDescriptorFactory::class.java, MinecraftComponentResolvers::class.java)
    }

    override fun apply(target: T) {
        when (target) {
            is Gradle -> {
                applyGradle(target)

                target.allprojects { project ->
                    project.plugins.apply(MinecraftCodevPlugin::class.java)
                }
            }

            is Settings -> target.gradle.plugins.apply(MinecraftCodevPlugin::class.java)
            is Project -> {
                applyGradle(target.gradle)

                target.plugins.apply(JavaPlugin::class.java)

                val minecraft = target.extensions.create("minecraft", MinecraftCodevExtension::class.java, target)
                target.dependencies.extensions.create("minecraftDependencyHandling", MinecraftDependencyExtension::class.java, attributesFactory)

                minecraft.runs.all { builder ->
                    target.tasks.register("${ApplicationPlugin.TASK_RUN_NAME}${builder.name.capitalized()}", JavaExec::class.java) { javaExec ->
                        val configuration = builder.build(target)

                        javaExec.args(configuration.arguments.get().map { it.parts.joinToString("") })
                        javaExec.jvmArgs(configuration.jvmArguments.get().map { it.parts.joinToString("") })
                        javaExec.environment(configuration.environment.get().mapValues { it.value.parts.joinToString("") })
                        javaExec.classpath = configuration.sourceSet.get().runtimeClasspath
                        javaExec.workingDir(configuration.workingDirectory)
                        javaExec.mainClass.set(configuration.mainClass)
                        javaExec.dependsOn(*configuration.beforeRunTasks.get().toTypedArray())

                        javaExec.group = ApplicationPlugin.APPLICATION_GROUP
                    }
                }
            }
        }
    }

    companion object {
        const val ACCESS_WIDENERS = "accessWideners"

        const val DOWNLOAD_ASSETS = "downloadAssets"

        @JvmField
        val OPERATING_SYSTEM_VERSION_PATTERN_ATTRIBUTE: Attribute<String> = Attribute.of("net.msrandom.minecraftcodev.operatingSystemVersionPattern", String::class.java)

        @JvmField
        val ACCESS_WIDENED_ATTRIBUTE: Attribute<Boolean> = Attribute.of("net.msrandom.minecraftcodev.accessWidened", Boolean::class.javaObjectType)

        internal lateinit var cacheProvider: CodevCacheProvider

        private val customDependencies = hashMapOf<Gradle, MutableSet<String>>()

        private val dependencyDescriptorFactories = DefaultDependencyDescriptorFactory::class.java.getDeclaredField("dependencyDescriptorFactories").apply {
            isAccessible = true
        }

        private val resolverFactories = DefaultArtifactDependencyResolver::class.java.getDeclaredField("resolverFactories").apply {
            isAccessible = true
        }

        private val domainObjectContext = DefaultConfiguration::class.java.getDeclaredField("domainObjectContext").apply {
            isAccessible = true
        }

        fun Project.unsafeResolveConfiguration(configuration: Configuration): Configuration {
            // Create new thread that can acquire a new binary store
            val thread = Thread {
                val workerLeaseService = serviceOf<WorkerLeaseService>()

                workerLeaseService.allowUncontrolledAccessToAnyProject {
                    workerLeaseService.withLocks(listOf(workerLeaseService.newWorkerLease())) {
                        // Actually resolve the configuration. Resolution is cached, so it can be used later from other threads.
                        configuration.resolvedConfiguration
                    }
                }
            }

            // Wait for resolution.
            thread.start()
            thread.join()

            return configuration
        }

        @Suppress("UNCHECKED_CAST")
        fun registerCustomDependency(
            name: String,
            gradle: Gradle,
            descriptorFactory: Class<out IvyDependencyDescriptorFactory>,
            componentResolvers: Class<out ComponentResolvers>
        ) {
            val dependencies = customDependencies.computeIfAbsent(gradle) { hashSetOf() }

            if (name !in dependencies) {
                gradle as GradleInternal
                val dependencyDescriptorFactory = gradle.serviceOf<DependencyDescriptorFactory>()
                val artifactDependencyResolver = gradle.serviceOf<ArtifactDependencyResolver>()
                val objectFactory = gradle.serviceOf<ObjectFactory>()
                val instantiatorFactory = gradle.serviceOf<InstantiatorFactory>()
                val directoryFileTreeFactory = gradle.serviceOf<DirectoryFileTreeFactory>()
                val patternSetFactory = gradle.serviceOf<Factory<PatternSet>>()
                val propertyFactory = gradle.serviceOf<PropertyFactory>()
                val filePropertyFactory = gradle.serviceOf<FilePropertyFactory>()
                val fileCollectionFactory = gradle.serviceOf<FileCollectionFactory>()
                val domainObjectCollectionFactory = gradle.serviceOf<DomainObjectCollectionFactory>()
                val instantiator = gradle.serviceOf<NamedObjectInstantiator>()
                val startParameterResolutionOverride = gradle.serviceOf<StartParameterResolutionOverride>()

                val ivyDependencyDescriptorFactories = dependencyDescriptorFactories[dependencyDescriptorFactory] as MutableList<IvyDependencyDescriptorFactory>
                val resolverProviderFactories = resolverFactories[artifactDependencyResolver] as MutableList<ResolverProviderFactory>

                ivyDependencyDescriptorFactories.add(objectFactory.newInstance(descriptorFactory))

                resolverProviderFactories.add(ResolverProviderFactory { context, resolvers ->
                    val configuration = context as? DefaultConfiguration
                    val project = configuration?.let { (domainObjectContext[it] as DomainObjectContext).project }

                    if (project != null) {
                        val newServices = DefaultServiceRegistry(project.services)

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

                        val cachePolicy = context.resolutionStrategy.cachePolicy

                        startParameterResolutionOverride.applyToCachePolicy(cachePolicy)

                        if (!::cacheProvider.isInitialized) {
                            cacheProvider = CodevCacheProvider(gradle)
                        }

                        newServices.add(ConfigurationInternal::class.java, configuration)
                        newServices.add(CachePolicy::class.java, cachePolicy)
                        newServices.add(ObjectFactory::class.java, projectObjectFactory)
                        newServices.add(CodevCacheProvider::class.java, cacheProvider)

                        newServices.addProvider(object {
                            fun createResolvers(artifactTypeRegistry: ArtifactTypeRegistry, calculatedValueContainerFactory: CalculatedValueContainerFactory) =
                                ComponentResolversChain(resolvers as List<ComponentResolvers>, artifactTypeRegistry, calculatedValueContainerFactory)
                        })

                        resolvers.add(projectObjectFactory.newInstance(componentResolvers))
                    }
                })

                dependencies.add(name)
            }
        }

        fun zipFileSystem(file: Path, create: Boolean = false): FileSystem =
            FileSystems.newFileSystem(URI.create("jar:${file.toUri()}"), if (create) mapOf("create" to true.toString()) else emptyMap())

        fun <T> Path.walk(action: Sequence<Path>.() -> T) = Files.walk(this).use {
            it.asSequence().action()
        }
    }
}
