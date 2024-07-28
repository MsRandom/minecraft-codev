package net.msrandom.minecraftcodev.core.utils

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.gradle.api.Project
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.internal.GradleInternal
import org.gradle.cache.scopes.GlobalScopedCacheBuilderFactory
import org.gradle.configurationcache.extensions.serviceOf
import java.io.File
import java.nio.file.Path
import kotlin.io.path.*

fun getCacheDirectory(project: Project): Path {
    val cacheBuilderFactory = (project.gradle as GradleInternal).serviceOf<GlobalScopedCacheBuilderFactory>()

    return cacheBuilderFactory.baseDirForCrossVersionCache("minecraft-codev").toPath()
}

private fun getVanillaExtractJarPath(
    project: Project,
    version: String,
    variant: String,
): Path =
    getCacheDirectory(project)
        .resolve("vanilla-extracts")
        .resolve("$variant-$version.${ArtifactTypeDefinition.JAR_TYPE}")

fun commonJarPath(
    project: Project,
    version: String,
) = getVanillaExtractJarPath(project, version, "common")

fun clientJarPath(
    project: Project,
    version: String,
) = getVanillaExtractJarPath(project, version, "client")

fun Project.cacheExpensiveOperation(
    prefix: String,
    inputFiles: Iterable<File>,
    outputPath: Path,
    action: () -> Unit,
) {
    val hashes =
        runBlocking {
            inputFiles.map {
                async { hashFile(it.toPath()).toList() }
            }.awaitAll()
        }

    val cumulativeHash =
        hashes.reduce { acc, bytes ->
            acc.zip(bytes).map { (a, b) -> (b + a * 31).toByte() }
        }.toByteArray()

    val cacheDirectory =
        getCacheDirectory(project)
            .resolve("cached-operations")
            .resolve(prefix)
            .resolve(hashToString(cumulativeHash))

    val cachedOutput = cacheDirectory.resolve(outputPath.fileName)
    val completionMarker = cacheDirectory.resolve("valid-marker")

    if (completionMarker.exists()) {
        outputPath.deleteIfExists()
        outputPath.createSymbolicLinkPointingTo(cachedOutput)

        return
    }

    cacheDirectory.createDirectories()

    cachedOutput.deleteIfExists()

    action()

    completionMarker.createFile()

    outputPath.createSymbolicLinkPointingTo(cachedOutput)
}
