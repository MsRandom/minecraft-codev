package net.msrandom.minecraftcodev.core

import kotlinx.serialization.json.Json
import net.msrandom.minecraftcodev.core.attributes.OperatingSystemDisambiguationRule
import net.msrandom.minecraftcodev.core.attributes.VersionPatternCompatibilityRule
import net.msrandom.minecraftcodev.core.caches.CodevCacheProvider
import net.msrandom.minecraftcodev.core.dependency.MinecraftDependencyFactory
import net.msrandom.minecraftcodev.core.dependency.MinecraftIvyDependencyDescriptorFactory
import net.msrandom.minecraftcodev.core.dependency.registerCustomDependency
import net.msrandom.minecraftcodev.core.resolve.MinecraftComponentResolvers
import org.apache.commons.lang3.StringUtils
import org.apache.commons.lang3.SystemUtils
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.attributes.Attribute
import org.gradle.api.internal.artifacts.dsl.dependencies.DefaultDependencyHandler
import org.gradle.api.internal.artifacts.query.ArtifactResolutionQueryFactory
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.api.internal.project.taskfactory.ITaskFactory
import org.gradle.api.internal.project.taskfactory.TaskIdentity
import org.gradle.api.internal.tasks.TaskDependencyResolveContext
import org.gradle.api.invocation.Gradle
import org.gradle.api.plugins.PluginAware
import org.gradle.configurationcache.extensions.serviceOf
import org.gradle.internal.operations.BuildOperationContext
import org.gradle.internal.os.OperatingSystem
import org.gradle.internal.work.WorkerLeaseService
import org.gradle.nativeplatform.OperatingSystemFamily
import sun.misc.Unsafe
import java.net.URI
import java.nio.file.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.streams.asSequence

open class MinecraftCodevPlugin<T : PluginAware> : Plugin<T> {
    private fun applyGradle(gradle: Gradle) = gradle.registerCustomDependency(
        "minecraft",
        MinecraftIvyDependencyDescriptorFactory::class.java,
        MinecraftDependencyFactory::class.java,
        MinecraftComponentResolvers::class.java
    )

    override fun apply(target: T) = applyPlugin(target, ::applyGradle) {
        // Patching artifact resolution queries to allow sources/javadoc artifact types from custom resolvers
        (Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }[null] as Unsafe).apply {
            putObject(dependencies, objectFieldOffset(DefaultDependencyHandler::class.java.getDeclaredField("resolutionQueryFactory")), ArtifactResolutionQueryFactory {
                objects.newInstance(CodevArtifactResolutionQuery::class.java)
            })
        }

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

        private val cacheProviders = ConcurrentHashMap<Gradle, CodevCacheProvider>()

        fun getCacheProvider(gradle: Gradle) = cacheProviders.computeIfAbsent(gradle, ::CodevCacheProvider)

        fun Project.addConfigurationResolutionDependencies(context: TaskDependencyResolveContext, configuration: Configuration) {
            context.add(configuration.buildDependencies)
            context.add(
                serviceOf<ITaskFactory>().create(
                    TaskIdentity.create(
                        "resolve" + StringUtils.capitalize(configuration.name),
                        ResolveConfiguration::class.java,
                        project as ProjectInternal
                    ),
                    arrayOf(configuration)
                )
            )
        }

        // FIXME This can cause deadlocks, should probably figure out a way around all of this.
        fun Project.unsafeResolveConfiguration(configuration: Configuration, warn: Boolean = false): Configuration {
            if (warn) {
                logger.debug("$configuration is being resolved unsafely, the build may freeze at this point. If it does, restart it\nThis will be fixed in future versions of minecraft-codev\n")
            }

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

        fun zipFileSystem(file: Path, create: Boolean = false): FileSystem {
            val uri = URI.create("jar:${file.toUri()}")

            return if (create) {
                FileSystems.newFileSystem(uri, mapOf("create" to true.toString()))
            } else {
                try {
                    val delegate = FileSystems.getFileSystem(uri)

                    object : FileSystem() {
                        override fun close() = Unit
                        override fun provider() = delegate.provider()
                        override fun isOpen() = delegate.isOpen
                        override fun isReadOnly() = delegate.isReadOnly
                        override fun getSeparator() = delegate.separator
                        override fun getRootDirectories() = delegate.rootDirectories
                        override fun getFileStores() = delegate.fileStores
                        override fun supportedFileAttributeViews() = delegate.supportedFileAttributeViews()
                        override fun getPath(first: String, vararg more: String?) = delegate.getPath(first, *more)
                        override fun getPathMatcher(syntaxAndPattern: String?) = delegate.getPathMatcher(syntaxAndPattern)
                        override fun getUserPrincipalLookupService() = delegate.userPrincipalLookupService
                        override fun newWatchService() = delegate.newWatchService()

                        override fun equals(other: Any?) = delegate == other
                        override fun hashCode() = delegate.hashCode()
                    }
                } catch (exception: FileSystemNotFoundException) {
                    FileSystems.newFileSystem(uri, emptyMap<String, Any>())
                }
            }
        }

        fun <T> Path.walk(action: Sequence<Path>.() -> T) = Files.walk(this).use {
            it.asSequence().action()
        }
    }
}
