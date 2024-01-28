package net.msrandom.minecraftcodev.core

import net.msrandom.minecraftcodev.core.dependency.MinecraftDependency
import net.msrandom.minecraftcodev.core.dependency.MinecraftDependencyImpl
import net.msrandom.minecraftcodev.core.utils.zipFileSystem
import org.gradle.api.Project
import org.gradle.api.internal.artifacts.dsl.CapabilityNotationParserFactory
import org.gradle.api.internal.attributes.ImmutableAttributesFactory
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.provider.ListProperty
import java.nio.file.FileSystem
import java.nio.file.Path
import kotlin.reflect.KProperty1

fun interface ModMatchingRule {
    fun detectModInfo(fileSystem: FileSystem): ModInfo?
}

@Suppress("unused")
abstract class MinecraftCodevExtension(private val project: Project, private val attributesFactory: ImmutableAttributesFactory) : ExtensionAware {
    private val capabilityNotationParser = CapabilityNotationParserFactory(false).create()!!

    val modInfoDetectionRules: ListProperty<ModMatchingRule> = project.objects.listProperty(ModMatchingRule::class.java)

    operator fun invoke(name: Any, version: String?): MinecraftDependency =
        MinecraftDependencyImpl(name.toString(), version.orEmpty(), null).apply {
            setAttributesFactory(attributesFactory)
            setCapabilityNotationParser(capabilityNotationParser)
        }

    operator fun invoke(name: Any) =
        invoke(name, null)

    operator fun invoke(notation: Map<String, Any>) =
        invoke(notation.getValue("name"), notation["version"]?.toString())

    fun call(name: Any, version: String?) = invoke(name, version)

    fun call(name: Any) = invoke(name)

    fun call(notation: Map<String, Any>) = invoke(notation)

    private fun sortDetectedInfo(info: List<ModInfo>) = info
        .groupBy(ModInfo::type)
        .mapValues { (_, info) ->
            info
                .asSequence()
                .groupBy(ModInfo::info)
                .map { (value, values) -> value to values.sumOf(ModInfo::score) }
                .sortedByDescending { (_, score) -> score }
                .map { (info, _) -> info }
                .toList()
        }

    fun detectModInfo(path: Path): DetectedModInfo {
        val info = zipFileSystem(path).use {
            modInfoDetectionRules.get().mapNotNull { rule ->
                rule.detectModInfo(it.base)
            }
        }

        val sortedInfo = sortDetectedInfo(info)

        return DetectedModInfo(
            sortedInfo.getOrDefault(ModInfoType.Platform, emptyList()),
            sortedInfo.getOrDefault(ModInfoType.Version, emptyList()),
            sortedInfo.getOrDefault(ModInfoType.Namespace, emptyList()),
        )
    }
}
