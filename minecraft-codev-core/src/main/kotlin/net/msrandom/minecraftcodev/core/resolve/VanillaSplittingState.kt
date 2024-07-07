package net.msrandom.minecraftcodev.core.resolve

import net.msrandom.minecraftcodev.core.resolve.MinecraftVersionMetadata.Rule.OperatingSystem
import net.msrandom.minecraftcodev.core.resolve.bundled.BundledClientJarSplitter
import net.msrandom.minecraftcodev.core.resolve.legacy.LegacyJarSplitter
import net.msrandom.minecraftcodev.core.utils.*
import org.apache.commons.lang3.SystemUtils
import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import org.gradle.nativeplatform.platform.internal.DefaultNativePlatform
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.copyTo
import kotlin.io.path.exists
import kotlin.io.path.notExists

fun setupCommon(
    project: Project,
    metadata: MinecraftVersionMetadata,
    output: Path? = null,
): List<String> {
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

fun setupClient(
    project: Project,
    output: Path,
    metadata: MinecraftVersionMetadata,
) {
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

fun getClientDependencies(
    project: Project,
    metadata: MinecraftVersionMetadata,
): List<Dependency> {
    val libs =
        metadata.libraries.filter { library ->
            if (library.rules.isEmpty()) {
                return@filter true
            }

            val rulesMatch =
                library.rules.all { rule ->
                    if (rule.os == null) {
                        return@all true
                    }

                    if (rule.action == MinecraftVersionMetadata.RuleAction.Allow) {
                        osMatches(rule.os)
                    } else {
                        !osMatches(rule.os)
                    }
                }

            rulesMatch
        }.map(MinecraftVersionMetadata.Library::name)

    return libs.map(project.dependencies::create)
}

private fun osMatches(os: OperatingSystem): Boolean {
    val systemName = DefaultNativePlatform.host().operatingSystem.toFamilyName()

    if (os.name != null && os.name != systemName) {
        return false
    }

    if (os.version != null && !(osVersion() matches Regex(os.version))) {
        return false
    }

    return os.arch == null || os.arch == SystemUtils.OS_ARCH
}
