package net.msrandom.minecraftcodev.core.utils

import com.google.common.hash.HashCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.gradle.api.Project
import org.gradle.api.artifacts.repositories.RepositoryResourceAccessor
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.io.path.*

fun <R : Any> RepositoryResourceAccessor.withCachedResource(
    cacheDirectory: File,
    relativePath: String,
    reader: (InputStream) -> R
): R? {
    val path = cacheDirectory.resolve("metadata-rule-download-cache").resolve(relativePath)

    return if (path.exists()) {
        path.inputStream().use(reader)
    } else {
        var result: R? = null

        withResource(relativePath) {
            val stream = it.buffered()

            stream.mark(Int.MAX_VALUE)
            path.toPath().parent.createDirectories()
            stream.copyTo(path.outputStream())

            stream.reset()

            result = reader(stream)
        }

        result
    }
}

fun getGlobalCacheDirectoryProvider(project: Project): Provider<Directory> =
    project.layout.dir(project.provider { getGlobalCacheDirectory(project) })

fun getGlobalCacheDirectory(project: Project): File =
    project.gradle.gradleUserHomeDir.resolve("caches/minecraft-codev")

fun getLocalCacheDirectoryProvider(project: Project): Provider<Directory> =
    project.rootProject.layout.buildDirectory.dir("minecraft-codev")

private fun getVanillaExtractJarPath(
    cacheDirectory: Path,
    version: String,
    variant: String,
): Path =
    cacheDirectory
        .resolve("vanilla-extracts")
        .resolve("$variant-$version.${ArtifactTypeDefinition.JAR_TYPE}")

internal fun commonJarPath(
    cacheDirectory: Path,
    version: String,
) = getVanillaExtractJarPath(cacheDirectory, version, "common")

fun Path.tryLink(target: Path) {
    try {
        createLinkPointingTo(target)
    } catch (_: Exception) {
        target.copyTo(this, StandardCopyOption.COPY_ATTRIBUTES)
    }
}

internal fun clientJarPath(
    cacheDirectory: Path,
    version: String,
) = getVanillaExtractJarPath(cacheDirectory, version, "client")

private val operationLocks = ConcurrentHashMap<Any, Lock>()

fun cacheExpensiveOperation(
    cacheDirectory: Path,
    operationName: String,
    inputFiles: Iterable<File>,
    vararg outputPaths: Path,
    generate: (List<Path>) -> Unit,
) {
    val outputPathsList = outputPaths.toList()

    val hashes =
        runBlocking {
            inputFiles.map {
                async { hashFile(it.toPath()).asBytes().toList() }
            }.awaitAll()
        }

    val cumulativeHash =
        hashes.reduce { acc, bytes ->
            acc.zip(bytes).map { (a, b) -> (b + a * 31).toByte() }
        }.toByteArray()

    val directoryName = HashCode.fromBytes(cumulativeHash).toString()

    val cachedOperationDirectoryName = cacheDirectory
        .resolve("cached-operations")
        .resolve(operationName)
        .resolve(directoryName)

    val allCached = outputPathsList.all {
        cachedOperationDirectoryName.resolve(it.fileName).exists()
    }

    if (allCached) {
        for (outputPath in outputPathsList) {
            val cachedOutput = cachedOperationDirectoryName.resolve(outputPath.fileName)

            outputPath.deleteIfExists()
            outputPath.tryLink(cachedOutput)
        }

        println("Cache hit for $operationName operation for $outputPathsList")

        return
    }

    println("Cache miss for $operationName operation for $outputPathsList")

    val lock = operationLocks.computeIfAbsent(outputPathsList) {
        ReentrantLock()
    }

    lock.withLock {
        val temporaryPaths = outputPathsList.map { outputPath ->
            val temporaryPath =
                Files.createTempFile("$operationName-${outputPath.nameWithoutExtension}", ".${outputPath.extension}")
            temporaryPath.deleteExisting()

            temporaryPath
        }

        generate(temporaryPaths)

        cachedOperationDirectoryName.createDirectories()

        for ((temporaryPath, outputPath) in temporaryPaths.zip(outputPathsList)) {
            val cachedOutput = cachedOperationDirectoryName.resolve(outputPath.fileName)

            temporaryPath.copyTo(cachedOutput, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    for (outputPath in outputPathsList) {
        val cachedOutput = cachedOperationDirectoryName.resolve(outputPath.fileName)

        outputPath.deleteIfExists()
        outputPath.tryLink(cachedOutput)
    }
}
