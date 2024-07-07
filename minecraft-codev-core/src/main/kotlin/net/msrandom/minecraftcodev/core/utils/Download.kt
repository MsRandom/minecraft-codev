package net.msrandom.minecraftcodev.core.utils

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
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.inputStream

private val resourceRepositoryCache = hashMapOf<RepositoryTransportFactory, ExternalResourceRepository>()
private val sha1Hash = MessageDigest.getInstance("SHA-1")

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

private fun sha1ToBytes(sha1: String) =
    sha1
        .windowed(2, 2, true)
        .map { it.toUByte(16).toByte() }
        .toByteArray()

private fun hashFile(file: Path) =
    file.inputStream().use { stream ->
        val buffer = ByteArray(8192)

        var read: Int

        while (stream.read(buffer).also { read = it } > 0) {
            sha1Hash.update(buffer, 0, read)
        }

        sha1Hash.digest()
    }

private fun fetchResource(
    uri: URI,
    repository: ExternalResourceRepository,
): ExternalResource = repository.resource(ExternalResourceName(uri))

private fun getCachedResource(
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
        if (!alwaysRefresh && sha1ToBytes(sha1) contentEquals hashFile(output)) {
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

        if (hash != null && hash contentEquals hashFile(output)) {
            return null
        }
    }

    return externalResource
}

fun download(
    project: Project,
    uri: URI,
    sha1: String?,
    output: Path,
    alwaysRefresh: Boolean = false,
) {
    val transportFactory = (project.gradle as GradleInternal).serviceOf<RepositoryTransportFactory>()

    val repository = getResourceRepository(transportFactory)

    val resource = getCachedResource(project, uri, sha1, repository, output, alwaysRefresh) ?: return

    output.parent.createDirectories()

    resource.writeTo(output.toFile())
}