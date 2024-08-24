package net.msrandom.minecraftcodev.core.utils

import com.google.common.hash.HashCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.gradle.api.GradleException
import org.gradle.api.InvalidUserCodeException
import org.gradle.api.Project
import org.gradle.api.internal.GradleInternal
import org.gradle.api.internal.artifacts.repositories.transport.RepositoryTransportFactory
import org.gradle.configurationcache.extensions.serviceOf
import org.gradle.internal.resource.ExternalResource
import org.gradle.internal.resource.ExternalResourceName
import org.gradle.internal.resource.ExternalResourceRepository
import org.gradle.internal.verifier.HttpRedirectVerifierFactory
import java.net.URI
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

private val resourceRepositoryCache = hashMapOf<RepositoryTransportFactory, ExternalResourceRepository>()

private fun getResourceRepository(transportFactory: RepositoryTransportFactory) =
    resourceRepositoryCache.computeIfAbsent(transportFactory) {
        it.createTransport(
            "https",
            "codev",
            emptyList(),
            HttpRedirectVerifierFactory.create(null, false, {
                throw InvalidUserCodeException(
                    "Using insecure protocol for Minecraft downloads, which is unsupported",
                )
            }, {
                throw InvalidUserCodeException(
                    "Insecure redirect to $it",
                )
            }),
        ).repository
    }

private suspend fun fetchResource(
    uri: URI,
    repository: ExternalResourceRepository,
): ExternalResource =
    coroutineScope {
        repository.resource(ExternalResourceName(uri))
    }

private suspend fun getCachedResource(
    project: Project,
    uri: URI,
    sha1: String?,
    repository: ExternalResourceRepository,
    output: Path,
    alwaysRefresh: Boolean,
): ExternalResource? {
    if (!output.exists()) {
        if (project.gradle.startParameter.isOffline) {
            throw GradleException("Tried to download or access $uri in offline mode")
        }

        return fetchResource(uri, repository)
    }

    if (sha1 != null) {
        if (!alwaysRefresh && checkHash(output, sha1)) {
            return null
        }

        if (project.gradle.startParameter.isOffline) {
            throw GradleException(
                "Cached version of $uri at $output has mismatched hash, expected $sha1, can not redownload in offline mode",
            )
        }

        return fetchResource(uri, repository)
    }

    if (project.gradle.startParameter.isOffline) {
        project.logger.debug("Using cached file {} without extra checksum validation due to being in offline mode", output)
        return null
    }

    val externalResource = fetchResource(uri, repository)

    if (!alwaysRefresh) {
        val hash = externalResource.metaData?.sha1?.toByteArray()

        if (hash != null && HashCode.fromBytes(hash) == hashFile(output)) {
            return null
        }
    }

    return externalResource
}

suspend fun download(
    project: Project,
    uri: URI,
    sha1: String?,
    output: Path,
    alwaysRefresh: Boolean = false,
) {
    val transportFactory = (project.gradle as GradleInternal).serviceOf<RepositoryTransportFactory>()

    val repository = getResourceRepository(transportFactory)

    withContext(Dispatchers.IO) {
        val resource = getCachedResource(project, uri, sha1, repository, output, alwaysRefresh) ?: return@withContext

        output.parent.createDirectories()

        resource.writeTo(output.toFile())
    }
}
