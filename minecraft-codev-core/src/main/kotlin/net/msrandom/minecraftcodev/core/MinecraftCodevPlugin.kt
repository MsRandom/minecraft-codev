package net.msrandom.minecraftcodev.core

import kotlinx.serialization.json.Json
import net.msrandom.minecraftcodev.core.attributes.OperatingSystemDisambiguationRule
import net.msrandom.minecraftcodev.core.attributes.VersionPatternCompatibilityRule
import net.msrandom.minecraftcodev.core.caches.CodevCacheProvider
import net.msrandom.minecraftcodev.core.dependency.ConfiguredDependencyMetadata
import net.msrandom.minecraftcodev.core.dependency.MinecraftIvyDependencyDescriptorFactory
import net.msrandom.minecraftcodev.core.resolve.ComponentResolversChainProvider
import net.msrandom.minecraftcodev.core.resolve.MinecraftComponentResolvers
import org.apache.commons.lang3.StringUtils
import org.apache.commons.lang3.SystemUtils
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
import org.gradle.api.internal.collections.DomainObjectCollectionFactory
import org.gradle.api.internal.file.FileCollectionFactory
import org.gradle.api.internal.file.FilePropertyFactory
import org.gradle.api.internal.file.collections.DirectoryFileTreeFactory
import org.gradle.api.internal.model.DefaultObjectFactory
import org.gradle.api.internal.model.NamedObjectInstantiator
import org.gradle.api.internal.provider.PropertyFactory
import org.gradle.api.invocation.Gradle
import org.gradle.api.model.ObjectFactory
import org.gradle.api.plugins.PluginAware
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.util.PatternSet
import org.gradle.configurationcache.extensions.serviceOf
import org.gradle.internal.Factory
import org.gradle.internal.instantiation.InstantiatorFactory
import org.gradle.internal.operations.BuildOperationContext
import org.gradle.internal.os.OperatingSystem
import org.gradle.internal.service.DefaultServiceRegistry
import org.gradle.internal.work.WorkerLeaseService
import org.gradle.nativeplatform.OperatingSystemFamily
import org.jetbrains.kotlin.commonizer.util.transitiveClosure
import org.jetbrains.kotlin.gradle.plugin.KotlinBasePluginWrapper
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSetContainer
import org.jetbrains.kotlin.gradle.plugin.KotlinTargetsContainer
import org.jetbrains.kotlin.gradle.utils.extendsFrom
import java.net.URI
import java.nio.file.FileSystem
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.asSequence

open class MinecraftCodevPlugin<T : PluginAware> : Plugin<T> {
    private fun applyGradle(gradle: Gradle) {
        registerCustomDependency("minecraft", gradle, MinecraftIvyDependencyDescriptorFactory::class.java, MinecraftComponentResolvers::class.java)
    }

    override fun apply(target: T) = applyPlugin(target, ::applyGradle) {
        extensions.create("minecraft", MinecraftCodevExtension::class.java)

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

        fun getCacheProvider(gradle: Gradle): CodevCacheProvider {
            if (!::cacheProvider.isInitialized) {
                cacheProvider = CodevCacheProvider(gradle)
            }

            return cacheProvider
        }

        // FIXME This can cause deadlocks, should probably figure out a way around all of this.
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

                        val resolversProvider by lazy {
                            ComponentResolversChain(resolvers as List<ComponentResolvers>, project.serviceOf(), project.serviceOf())
                        }

                        newServices.add(ConfigurationInternal::class.java, configuration)
                        newServices.add(CachePolicy::class.java, cachePolicy)
                        newServices.add(ObjectFactory::class.java, projectObjectFactory)
                        newServices.add(CodevCacheProvider::class.java, getCacheProvider(gradle))

                        newServices.add(ComponentResolversChainProvider::class.java, ComponentResolversChainProvider { resolversProvider })

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

        fun <R> BuildOperationContext.callWithStatus(action: () -> R): R {
            progress("STARTING")

            val result = try {
                action()
            } catch (failure: Throwable) {
                setStatus("FAILED")
                failed(failure)
                throw failure
            }

            setStatus("DONE")

            return result
        }

        fun zipFileSystem(file: Path, create: Boolean = false): FileSystem =
            FileSystems.newFileSystem(URI.create("jar:${file.toUri()}"), if (create) mapOf("create" to true.toString()) else emptyMap())

        fun <T> Path.walk(action: Sequence<Path>.() -> T) = Files.walk(this).use {
            it.asSequence().action()
        }

        /**
         * Find a matching configuration name by attempting to find which source set the resolving module configuration is from.
         */
        fun Project.getSourceSetConfigurationName(dependency: ConfiguredDependencyMetadata, defaultConfiguration: String) = dependency.relatedConfiguration ?: run {
            val moduleConfiguration = dependency.getModuleConfiguration()
            if (moduleConfiguration == null) {
                defaultConfiguration
            } else {
                // First, look for a Java source set. We can't know if this is an exact match as Java source sets don't keep a list of related configurations, but it's good enough.
                var owningSourceSetName = extensions
                    .findByType(SourceSetContainer::class.java)
                    ?.firstOrNull { moduleConfiguration.startsWith(it.name) }
                    ?.name

                if (owningSourceSetName == null) {
                    // If that fails, look for a matching Kotlin source set.
                    owningSourceSetName = extensions
                        .findByType(KotlinSourceSetContainer::class.java)
                        ?.sourceSets
                        ?.firstOrNull { moduleConfiguration in it.relatedConfigurationNames }
                        ?.name

                    if (owningSourceSetName == null) {
                        // If all fails, look for a matching Kotlin compilation as a last resort.
                        owningSourceSetName = extensions
                            .findByType(KotlinTargetsContainer::class.java)
                            ?.targets
                            ?.firstNotNullOfOrNull { target ->
                                target.compilations.firstOrNull {
                                    moduleConfiguration in it.relatedConfigurationNames
                                }
                            }
                            ?.name
                    }
                }

                // If no related source set/compilation was found, we can return the default configuration, and it will error later if it doesn't exist.
                owningSourceSetName?.let { it + StringUtils.capitalize(defaultConfiguration) } ?: defaultConfiguration
            }
        }

        private fun KotlinCompilation<*>.configurationBase() = target.disambiguationClassifier +
                name.takeIf { it != KotlinCompilation.MAIN_COMPILATION_NAME }?.let(StringUtils::capitalize).orEmpty()

        fun Project.createSourceSetElements(action: (name: String) -> Unit) {
            extensions.findByType(SourceSetContainer::class.java)?.let {
                action("")

                it.all { sourceSet ->
                    @Suppress("UnstableApiUsage")
                    if (!SourceSet.isMain(sourceSet)) {
                        action(sourceSet.name)
                    }
                }
            }

            plugins.withType(KotlinBasePluginWrapper::class.java) {
                extensions.getByType(KotlinSourceSetContainer::class.java).sourceSets.all { sourceSet ->
                    if (sourceSet.name == SourceSet.MAIN_SOURCE_SET_NAME) {
                        action("")
                    } else {
                        action(sourceSet.name)
                    }
                }

                extensions.getByType(KotlinTargetsContainer::class.java).targets.all { target ->
                    target.compilations.all { compilation ->
                        action(compilation.configurationBase())
                    }
                }
            }
        }

        fun Project.createSourceSetConfigurations(name: String) {
            createSourceSetElements { setName ->
                val configurationName = if (setName.isEmpty()) name else setName + StringUtils.capitalize(name)
                configurations.maybeCreate(configurationName).apply {
                    isCanBeConsumed = false
                    isTransitive = false
                }
            }

            extendKotlinConfigurations(name)
        }

        fun Project.extendKotlinConfigurations(name: String) {
            plugins.withType(KotlinBasePluginWrapper::class.java) {
                afterEvaluate {
                    extensions.getByType(KotlinTargetsContainer::class.java).targets.all { target ->
                        target.compilations.all { compilation ->
                            val configuration = configurations.named(compilation.configurationBase() + StringUtils.capitalize(name))
                            val defaultSourceSet = compilation.defaultSourceSet

                            val allSourceSets = compilation.kotlinSourceSets + compilation.kotlinSourceSets.flatMapTo(mutableSetOf()) {
                                transitiveClosure(defaultSourceSet) { dependsOn }
                            }

                            for (sourceSet in allSourceSets) {
                                if (sourceSet != defaultSourceSet) {
                                    if (sourceSet.name == SourceSet.MAIN_SOURCE_SET_NAME) {
                                        configuration.extendsFrom(configurations.named(name))
                                    } else {
                                        configuration.extendsFrom(configurations.named(name))
                                    }
                                }
                            }
                        }
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
