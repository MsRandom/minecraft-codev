package net.msrandom.minecraftcodev.core.resolve

import net.msrandom.minecraftcodev.core.resolve.MinecraftVersionMetadata.Rule.OperatingSystem
import net.msrandom.minecraftcodev.core.resolve.bundled.BundledClientJarSplitter
import net.msrandom.minecraftcodev.core.resolve.legacy.LegacyJarSplitter
import net.msrandom.minecraftcodev.core.utils.*
import org.apache.commons.lang3.SystemUtils
import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.copyTo
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.notExists

suspend fun setupCommon(
    project: Project,
    metadata: MinecraftVersionMetadata,
    output: Path? = null,
): List<String> {
    output?.deleteIfExists()

    val (extractedServer, isBundled, libraries) = getExtractionState(project, metadata)!!

    return if (isBundled) {
        if (output != null) {
            extractedServer.copyTo(output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
        }

        libraries
    } else {
        val commonJarPath = commonJarPath(project, metadata.id)

        if (commonJarPath.notExists() && clientJarPath(project, metadata.id).notExists()) {
            LegacyJarSplitter.split(project, metadata, extractedServer)
        }

        if (output != null) {
            commonJarPath.copyTo(output)
        }

        libraries + "net.msrandom:side-annotations:1.0.0"
    }
}

suspend fun setupClient(
    project: Project,
    output: Path,
    metadata: MinecraftVersionMetadata,
) {
    output.deleteIfExists()

    val clientJarPath = clientJarPath(project, metadata.id)

    if (clientJarPath.exists()) {
        clientJarPath.copyTo(output)

        return
    }

    val (extractedServer, isBundled) =
        getExtractionState(
            project,
            metadata,
        )!!

    if (isBundled) {
        BundledClientJarSplitter.split(project, metadata, extractedServer)

        clientJarPath.copyTo(output)
    } else {
        LegacyJarSplitter.split(project, metadata, extractedServer).client

        clientJarPath.copyTo(output)
    }
}

fun rulesMatch(rules: List<MinecraftVersionMetadata.Rule>): Boolean {
    if (rules.isEmpty()) {
        return true
    }

    var allowed = false

    for (rule in rules) {
        if (rule.action == MinecraftVersionMetadata.RuleAction.Allow) {
            if (rule.os == null || osMatches(rule.os)) {
                allowed = true
            }
        } else {
            if (rule.os == null || osMatches(rule.os)) {
                allowed = false
            }
        }
    }

    return allowed
}

fun getAllDependencies(metadata: MinecraftVersionMetadata) =
    metadata.libraries.filter { rulesMatch(it.rules) }.map(MinecraftVersionMetadata.Library::name)

suspend fun getClientDependencies(
    project: Project,
    metadata: MinecraftVersionMetadata,
): List<Dependency> {
    val (_, _, serverLibraries) = getExtractionState(project, metadata)!!

    return (getAllDependencies(metadata) - serverLibraries.toSet()).map(project.dependencies::create)
}

private fun osMatches(os: OperatingSystem): Boolean {
    if (os.name != null && os.name != osName()) {
        return false
    }

    if (os.version != null && !(osVersion() matches Regex(os.version))) {
        return false
    }

    return os.arch == null || os.arch == SystemUtils.OS_ARCH
}
