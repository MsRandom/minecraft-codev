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
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.io.path.*

fun <R : Any> RepositoryResourceAccessor.withCachedResource(cacheDirectory: File, relativePath: String, reader: (InputStream) -> R): R? {
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
        createSymbolicLinkPointingTo(target)
    } catch (exception: SecurityException) {
        if (exception.message?.let { "symbolic" in it } != true) {
            throw exception
        }

        try {
            createLinkPointingTo(target)
        } catch (exception: FileSystemException) {
            if (exception.message?.let { "Invalid cross-device link" in it } != true) {
                throw exception
            }

            target.copyTo(this, StandardCopyOption.COPY_ATTRIBUTES)
        }
    }
}

internal fun clientJarPath(
    cacheDirectory: Path,
    version: String,
) = getVanillaExtractJarPath(cacheDirectory, version, "client")

private val operationLocks = ConcurrentHashMap<Path, Lock>()

fun cacheExpensiveOperation(
    cacheDirectory: Path,
    operationName: String,
    inputFiles: Iterable<File>,
    outputPath: Path,
    generate: (Path) -> Unit,
) {
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

    val cachedOutput = cachedOperationDirectoryName.resolve(outputPath.fileName)

    if (cachedOutput.exists()) {
        outputPath.deleteIfExists()
        outputPath.tryLink(cachedOutput)

        println("Cache hit for $operationName operation for $outputPath")

        return
    }

    println("Cache miss for $operationName operation for $outputPath")

    val lock = operationLocks.computeIfAbsent(outputPath) {
        ReentrantLock()
    }

    lock.withLock {
        val temporaryPath =
            Files.createTempFile("$operationName-${outputPath.nameWithoutExtension}", ".${outputPath.extension}")
        temporaryPath.deleteExisting()

        generate(temporaryPath)

        cachedOperationDirectoryName.createDirectories()
        temporaryPath.copyTo(cachedOutput, StandardCopyOption.COPY_ATTRIBUTES)
    }

    outputPath.deleteIfExists()
    outputPath.tryLink(cachedOutput)
}
