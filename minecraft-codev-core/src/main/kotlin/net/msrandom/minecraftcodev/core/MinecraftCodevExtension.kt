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

    private fun <T : ModInfo> sortDetectedInfo(info: List<T>, factory: (String, Int) -> T, valueGetter: (T) -> String) = info
        .groupBy(valueGetter)
        .map { (platform, values) -> factory(platform, values.sumOf(ModInfo::score)) }
        .sortedByDescending(ModInfo::score)
        .map(valueGetter)

    fun detectModInfo(path: Path): DetectedModInfo {
        val potentialPlatforms = mutableListOf<ModPlatformInfo>()
        val potentialNamespaces = mutableListOf<ModMappingNamespaceInfo>()

        zipFileSystem(path).use {
            for (rule in modInfoDetectionRules.get()) {
                val modInfo = rule.detectModInfo(it.base) ?: continue

                when (modInfo) {
                    is ModPlatformInfo -> potentialPlatforms.add(modInfo)
                    is ModMappingNamespaceInfo -> potentialNamespaces.add(modInfo)
                }
            }
        }

        potentialNamespaces.groupBy(ModMappingNamespaceInfo::namespace).map { (namespace, values) ->
            ModPlatformInfo(namespace, values.sumOf(ModInfo::score))
        }

        return DetectedModInfo(
            sortDetectedInfo(potentialPlatforms, ::ModPlatformInfo, ModPlatformInfo::platform),
            sortDetectedInfo(potentialNamespaces, ::ModMappingNamespaceInfo, ModMappingNamespaceInfo::namespace)
        )
    }
}
