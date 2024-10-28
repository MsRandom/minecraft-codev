package net.msrandom.minecraftcodev.core.utils

import org.gradle.api.Project
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import java.io.File
import java.nio.file.Path

fun getCacheDirectoryProvider(project: Project): Provider<Directory> =
    project.layout.dir(project.provider { project.gradle.gradleUserHomeDir.resolve("caches/minecraft-codev") })

fun getCacheDirectory(project: Project): File =
    project.gradle.gradleUserHomeDir.resolve("caches/minecraft-codev")

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

internal fun clientJarPath(
    cacheDirectory: Path,
    version: String,
) = getVanillaExtractJarPath(cacheDirectory, version, "client")
