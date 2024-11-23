package net.msrandom.minecraftcodev.core.resolve

import net.msrandom.minecraftcodev.core.resolve.MinecraftVersionMetadata.Rule.OperatingSystem
import net.msrandom.minecraftcodev.core.resolve.bundled.BundledClientJarSplitter
import net.msrandom.minecraftcodev.core.resolve.legacy.LegacyJarSplitter
import net.msrandom.minecraftcodev.core.utils.clientJarPath
import net.msrandom.minecraftcodev.core.utils.commonJarPath
import net.msrandom.minecraftcodev.core.utils.osName
import net.msrandom.minecraftcodev.core.utils.osVersion
import org.apache.commons.lang3.SystemUtils
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.copyTo
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.notExists

internal fun setupCommon(
    cacheDirectory: Path,
    metadata: MinecraftVersionMetadata,
    isOffline: Boolean,
    output: Path? = null,
): Iterable<String> {
    output?.deleteIfExists()

    val (extractedServer, isBundled, libraries) = getExtractionState(cacheDirectory, metadata, isOffline)!!

    return if (isBundled) {
        if (output != null) {
            extractedServer.copyTo(output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
            addMinecraftMarker(output)
        }

        libraries
    } else {
        val commonJarPath = commonJarPath(cacheDirectory, metadata.id)

        if (commonJarPath.notExists() && clientJarPath(cacheDirectory, metadata.id).notExists()) {
            LegacyJarSplitter.split(cacheDirectory, metadata, extractedServer, isOffline)
        }

        if (output != null) {
            commonJarPath.copyTo(output)
            addMinecraftMarker(output)
        }

        libraries + "net.msrandom:side-annotations:1.0.0"
    }
}

internal fun setupClient(
    cacheDirectory: Path,
    output: Path,
    metadata: MinecraftVersionMetadata,
    isOffline: Boolean,
) {
    output.deleteIfExists()

    val clientJarPath = clientJarPath(cacheDirectory, metadata.id)

    if (clientJarPath.exists()) {
        clientJarPath.copyTo(output)

        return
    }

    val (extractedServer, isBundled) =
        getExtractionState(
            cacheDirectory,
            metadata,
            isOffline,
        )!!

    if (isBundled) {
        BundledClientJarSplitter.split(cacheDirectory, metadata, extractedServer, isOffline)

        clientJarPath.copyTo(output)
        addMinecraftMarker(output)
    } else {
        LegacyJarSplitter.split(cacheDirectory, metadata, extractedServer, isOffline).client

        clientJarPath.copyTo(output)
        addMinecraftMarker(output)
    }
}

fun getAllDependencies(metadata: MinecraftVersionMetadata) =
    metadata.libraries.filter { rulesMatch(it.rules) }.map { it.name.toString() }

private fun osMatches(os: OperatingSystem): Boolean {
    if (os.name != null && os.name != osName()) {
        return false
    }

    if (os.version != null && !(osVersion() matches Regex(os.version))) {
        return false
    }

    return os.arch == null || os.arch == SystemUtils.OS_ARCH
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

fun getClientDependencies(
    cacheDirectory: Path,
    metadata: MinecraftVersionMetadata,
    isOffline: Boolean,
): Iterable<String> {
    val (_, _, serverLibraries) = getExtractionState(cacheDirectory, metadata, isOffline)!!

    return getAllDependencies(metadata) - serverLibraries.toSet()
}
