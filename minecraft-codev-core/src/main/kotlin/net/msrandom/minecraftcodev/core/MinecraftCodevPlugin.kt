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
import java.net.URI
import java.nio.file.FileSystem
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.asSequence

open class MinecraftCodevPlugin<T : PluginAware> : Plugin<T> {
    private fun applyGradle(gradle: Gradle) = gradle.registerCustomDependency(
        "minecraft",
        MinecraftIvyDependencyDescriptorFactory::class.java,
        MinecraftDependencyFactory::class.java,
        MinecraftComponentResolvers::class.java
    )

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

        fun getCacheProvider(gradle: Gradle): CodevCacheProvider {
            if (!::cacheProvider.isInitialized) {
                cacheProvider = CodevCacheProvider(gradle)
            }

            return cacheProvider
        }

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
        @Deprecated("In favor of artifact task dependencies")
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
    }
}
