package net.msrandom.minecraftcodev.core.utils

import org.gradle.api.Project
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.internal.GradleInternal
import org.gradle.cache.scopes.GlobalScopedCacheBuilderFactory
import org.gradle.configurationcache.extensions.serviceOf
import java.nio.file.Path

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
