package net.msrandom.minecraftcodev.core

import net.msrandom.minecraftcodev.core.utils.zipFileSystem
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import java.nio.file.FileSystem
import java.nio.file.Path

open class ResolutionData<T>(
    val visitor: T,
)

fun interface ResolutionRule<T : ResolutionData<*>> {
    fun load(
        path: Path,
        extension: String,
        data: T,
    ): Boolean
}

fun interface ZipResolutionRule<T : ResolutionData<*>> {
    fun load(
        path: Path,
        fileSystem: FileSystem,
        isJar: Boolean,
        data: T,
    ): Boolean
}

fun <T : ResolutionData<*>> handleZipRules(
    zipResolutionRules: Iterable<ZipResolutionRule<T>>,
    path: Path,
    extension: String,
    data: T,
): Boolean {
    val isJar = extension == "jar"

    if (!isJar && extension != "zip") {
        return false
    }

    zipFileSystem(path).use {
        for (rule in zipResolutionRules) {
            if (rule.load(path, it, isJar, data)) {
                return true
            }
        }
    }

    return false
}

abstract class ZipResolutionRuleHandler<T : ResolutionData<*>, U : ZipResolutionRule<T>>(
    private val zipResolutionRules: Iterable<U>,
) : ResolutionRule<T> {
    override fun load(
        path: Path,
        extension: String,
        data: T,
    ) = handleZipRules(zipResolutionRules, path, extension, data)
}

@Suppress("UNCHECKED_CAST")
fun <T : ResolutionData<*>> ObjectFactory.zipResolutionRules() =
    listProperty(ZipResolutionRule::class.java) as ListProperty<ZipResolutionRule<T>>

@Suppress("UNCHECKED_CAST")
fun <T : ResolutionData<*>> ObjectFactory.resolutionRules(
    zipResolutionRules: ListProperty<ZipResolutionRule<T>>,
): ListProperty<ResolutionRule<T>> {
    val rules = listProperty(ResolutionRule::class.java) as ListProperty<ResolutionRule<T>>

    rules.add { path, extension, data ->
        handleZipRules(zipResolutionRules.get(), path, extension, data)
    }

    return rules
}
