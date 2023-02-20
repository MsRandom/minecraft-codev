package net.msrandom.minecraftcodev.forge

import net.msrandom.minecraftcodev.core.utils.addConfigurationResolutionDependencies
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.configurationcache.extensions.serviceOf
import org.gradle.internal.work.WorkerLeaseService

/**
 * Resolves configuration from any context, including within the resolution of another configuration
 * Not to be used during artifact resolution, as that can potentially cause deadlocks
 * Do NOT use this when unnecessary, always prefer task dependencies, see [Project.addConfigurationResolutionDependencies]
 * Reduce usages of this as much as possible.
 * @param configuration The configuration to resolve
 * @return The same [configuration] passed in, after resolution
 */
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
