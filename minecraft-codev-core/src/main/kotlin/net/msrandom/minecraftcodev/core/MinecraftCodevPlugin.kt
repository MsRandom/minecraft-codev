package net.msrandom.minecraftcodev.core

import kotlinx.serialization.json.Json
import net.msrandom.minecraftcodev.core.attributes.OperatingSystemDisambiguationRule
import net.msrandom.minecraftcodev.core.attributes.VersionPatternCompatibilityRule
import net.msrandom.minecraftcodev.core.caches.CodevCacheProvider
import net.msrandom.minecraftcodev.core.dependency.ConfiguredDependencyMetadata
import net.msrandom.minecraftcodev.core.dependency.MinecraftDependencyExtension
import net.msrandom.minecraftcodev.core.dependency.MinecraftIvyDependencyDescriptorFactory
import net.msrandom.minecraftcodev.core.resolve.MinecraftComponentResolvers
import org.apache.commons.lang3.StringUtils
import org.gradle.api.Named
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
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.PluginAware
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.util.PatternSet
import org.gradle.configurationcache.extensions.serviceOf
import org.gradle.initialization.layout.GlobalCacheDir
import org.gradle.internal.Factory
import org.gradle.internal.impldep.org.apache.commons.lang.SystemUtils
import org.gradle.internal.instantiation.InstantiatorFactory
import org.gradle.internal.model.CalculatedValueContainerFactory
import org.gradle.internal.os.OperatingSystem
import org.gradle.internal.service.DefaultServiceRegistry
import org.gradle.internal.work.WorkerLeaseService
import org.gradle.nativeplatform.OperatingSystemFamily
import java.net.URI
import java.nio.file.FileSystem
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import javax.inject.Inject
import kotlin.streams.asSequence

open class MinecraftCodevPlugin<T : PluginAware> @Inject constructor(private val attributesFactory: ImmutableAttributesFactory, cacheDir: GlobalCacheDir) : Plugin<T> {
    val cache: Path = cacheDir.dir.toPath().resolve("minecraft-codev")

    // Asset indexes and objects
    val assets: Path = cache.resolve("assets")

    // Legacy assets
    val resources: Path = cache.resolve("resources")

    private fun applyGradle(gradle: Gradle) {
        registerCustomDependency("minecraft", gradle, MinecraftIvyDependencyDescriptorFactory::class.java, MinecraftComponentResolvers::class.java)
    }

    override fun apply(target: T) = applyPlugin(target, ::applyGradle) {
        plugins.apply(JavaPlugin::class.java)

        extensions.create("minecraft", MinecraftCodevExtension::class.java)
        dependencies.extensions.create("minecraftDependencyHandling", MinecraftDependencyExtension::class.java, attributesFactory)

        project.dependencies.attributesSchema { schema ->
            schema.attribute(OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE) {
                it.disambiguationRules.add(OperatingSystemDisambiguationRule::class.java)
            }

            schema.attribute(OPERATING_SYSTEM_VERSION_PATTERN_ATTRIBUTE) {
                it.compatibilityRules.add(VersionPatternCompatibilityRule::class.java)
            }
        }

        project.configurations.all { configuration ->
            configuration.attributes {
                it.attribute(OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE, project.objects.named(OperatingSystem.current().familyName))
            }
        }
    }

    companion object {
        const val DOWNLOAD_ASSETS = "downloadAssets"

        @JvmField
        val OPERATING_SYSTEM_VERSION_PATTERN_ATTRIBUTE: Attribute<String> = Attribute.of("net.msrandom.minecraftcodev.operatingSystemVersionPattern", String::class.java)

        val json = Json {
            ignoreUnknownKeys = true
        }

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
                            @Suppress("unused")
                            fun createResolvers(artifactTypeRegistry: ArtifactTypeRegistry, calculatedValueContainerFactory: CalculatedValueContainerFactory) =
                                ComponentResolversChain(resolvers as List<ComponentResolvers>, artifactTypeRegistry, calculatedValueContainerFactory)
                        })

                        resolvers.add(projectObjectFactory.newInstance(componentResolvers))
                    }
                })

                dependencies.add(name)
            }
        }

        fun osVersion(): String {
            val version = SystemUtils.OS_VERSION
            val versionEnd = version.indexOf('-')
            return if (versionEnd < 0) version else version.substring(0, versionEnd)
        }

        fun zipFileSystem(file: Path, create: Boolean = false): FileSystem =
            FileSystems.newFileSystem(URI.create("jar:${file.toUri()}"), if (create) mapOf("create" to true.toString()) else emptyMap())

        fun <T> Path.walk(action: Sequence<Path>.() -> T) = Files.walk(this).use {
            it.asSequence().action()
        }

        fun Project.getSourceSetConfigurationName(dependency: ConfiguredDependencyMetadata, defaultConfiguration: String) = dependency.relatedConfiguration ?: run {
            val moduleConfiguration = dependency.getModuleConfiguration()
            if (moduleConfiguration == null) {
                defaultConfiguration
            } else {
                var owningSourceSet: SourceSet? = null
                for (sourceSet in project.extensions.getByType(SourceSetContainer::class.java)) {
                    if (moduleConfiguration.startsWith(sourceSet.name)) {
                        owningSourceSet = sourceSet
                        break
                    }
                }

                owningSourceSet?.let { "${it.name}${StringUtils.capitalize(defaultConfiguration)}" } ?: defaultConfiguration
            }
        }

        fun Project.createSourceSetConfigurations(name: String) {
            configurations.create(name) {
                it.isCanBeConsumed = false
                it.isTransitive = false
            }

            extensions.getByType(SourceSetContainer::class.java).all { sourceSet ->
                @Suppress("UnstableApiUsage")
                if (!SourceSet.isMain(sourceSet)) {
                    configurations.create("${sourceSet.name}${StringUtils.capitalize(name)}") {
                        it.isCanBeConsumed = false
                        it.isTransitive = false
                    }
                }
            }
        }

        fun <T : PluginAware> Plugin<T>.applyPlugin(target: T, action: Project.() -> Unit) = applyPlugin(target, {}, action)

        fun <T : PluginAware> Plugin<T>.applyPlugin(target: T, gradleSetup: (Gradle) -> Unit, action: Project.() -> Unit) = when (target) {
            is Gradle -> {
                gradleSetup(target)

                target.allprojects {
                    target.plugins.apply(javaClass)
                }
            }

            is Settings -> target.gradle.apply {
                it.plugin(javaClass)
            }

            is Project -> {
                gradleSetup(target.gradle)
                target.action()
            }

            else -> Unit
        }
    }
}

inline fun <reified T : Named> ObjectFactory.named(name: String): T = named(T::class.java, name)

/**
 * Mostly functions as a holder for other modules' extensions.
 */
abstract class MinecraftCodevExtension : ExtensionAware
