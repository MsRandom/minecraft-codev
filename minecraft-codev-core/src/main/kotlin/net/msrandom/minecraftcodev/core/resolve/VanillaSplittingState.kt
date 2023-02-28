package net.msrandom.minecraftcodev.core.resolve

import net.msrandom.minecraftcodev.core.caches.CodevCacheManager
import net.msrandom.minecraftcodev.core.resolve.bundled.BundledClientJarSplitter
import net.msrandom.minecraftcodev.core.resolve.legacy.LegacyJarSplitter
import net.msrandom.minecraftcodev.core.utils.callWithStatus
import net.msrandom.minecraftcodev.core.utils.createDeterministicCopy
import org.gradle.internal.component.external.model.ModuleComponentArtifactIdentifier
import org.gradle.internal.hash.ChecksumService
import org.gradle.internal.operations.*
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories

private val legacySplitJarStates = ConcurrentHashMap<Pair<Path, Path>, Lazy<JarSplittingResult>>()
private val bundledClientJarStates = ConcurrentHashMap<Pair<Path, Path>, Lazy<Path>>()
private val bundledCommonJarStates = ConcurrentHashMap<Path, Lazy<Path>>()

fun getCommonJar(
    cacheManager: CodevCacheManager,
    buildOperationExecutor: BuildOperationExecutor,
    checksumService: ChecksumService,
    pathFunction: (artifact: ModuleComponentArtifactIdentifier, sha1: String) -> Path,
    manifest: MinecraftVersionMetadata,
    serverJar: File,
    clientJar: () -> File
): Lazy<Path> {
    val (extractedServer, isBundled) = getExtractionState(cacheManager, buildOperationExecutor, manifest, { serverJar }, clientJar).value!!
    return if (isBundled) {
        bundledCommonJarStates.computeIfAbsent(extractedServer) {
            lazy {
                val path = pathFunction(LegacyJarSplitter.makeId(MinecraftComponentResolvers.COMMON_MODULE, manifest.id), checksumService.sha1(extractedServer.toFile()).toString())
                path.parent.createDirectories()
                extractedServer.copyTo(path, StandardCopyOption.REPLACE_EXISTING)
                path
            }
        }
    } else {
        val legacySplitState = getLegacySplitJarsState(buildOperationExecutor, checksumService, pathFunction, manifest, clientJar().toPath(), extractedServer)
        lazy { legacySplitState.value.common }
    }
}

fun getClientJar(
    cacheManager: CodevCacheManager,
    buildOperationExecutor: BuildOperationExecutor,
    checksumService: ChecksumService,
    pathFunction: (artifact: ModuleComponentArtifactIdentifier, sha1: String) -> Path,
    manifest: MinecraftVersionMetadata,
    clientJar: File,
    serverJar: File
): Path {
    val (extractedServer, isBundled) = getExtractionState(cacheManager, buildOperationExecutor, manifest, { serverJar }) { clientJar }.value!!

    return if (isBundled) {
        val commonJar = getCommonJar(cacheManager, buildOperationExecutor, checksumService, pathFunction, manifest, serverJar) { clientJar }.value
        getBundledClientJarState(buildOperationExecutor, checksumService, pathFunction, manifest, clientJar.toPath(), commonJar).value
    } else {
        getLegacySplitJarsState(buildOperationExecutor, checksumService, pathFunction, manifest, clientJar.toPath(), extractedServer).value.client
    }
}

private fun getBundledClientJarState(
    buildOperationExecutor: BuildOperationExecutor,
    checksumService: ChecksumService,
    pathFunction: (artifact: ModuleComponentArtifactIdentifier, sha1: String) -> Path,
    manifest: MinecraftVersionMetadata,
    clientJar: Path,
    serverJar: Path
): Lazy<Path> = bundledClientJarStates.computeIfAbsent(clientJar to serverJar) {
    lazy {
        buildOperationExecutor.call(object : CallableBuildOperation<Path> {
            val result = Files.createTempFile("split-client-", ".tmp.jar")

            override fun description() = BuildOperationDescriptor
                .displayName("Making split client from $clientJar and $serverJar")
                .progressDisplayName("Output: $result")
                .metadata(BuildOperationCategory.TASK)

            override fun call(context: BuildOperationContext) = context.callWithStatus {
                BundledClientJarSplitter.split(checksumService, pathFunction, manifest.id, result, clientJar, serverJar)
            }
        })
    }
}

private fun getLegacySplitJarsState(
    buildOperationExecutor: BuildOperationExecutor,
    checksumService: ChecksumService,
    pathFunction: (artifact: ModuleComponentArtifactIdentifier, sha1: String) -> Path,
    manifest: MinecraftVersionMetadata,
    clientJar: Path,
    serverJar: Path
): Lazy<JarSplittingResult> = legacySplitJarStates.computeIfAbsent(clientJar to serverJar) {
    lazy {
        buildOperationExecutor.call(object : CallableBuildOperation<JarSplittingResult> {
            val common = serverJar.createDeterministicCopy("split-common-", ".tmp.jar")
            val client = clientJar.createDeterministicCopy("split-client-", ".tmp.jar")

            override fun description() = BuildOperationDescriptor
                .displayName("Splitting $clientJar and $serverJar")
                .progressDisplayName("Common: $common, Client: $client")
                .metadata(BuildOperationCategory.TASK)

            override fun call(context: BuildOperationContext) = context.callWithStatus {
                LegacyJarSplitter.split(checksumService, pathFunction, manifest.id, common, client, clientJar, serverJar)
            }
        })
    }
}

data class JarSplittingResult(val common: Path, val client: Path)
