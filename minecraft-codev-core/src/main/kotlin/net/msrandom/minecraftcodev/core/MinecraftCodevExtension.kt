package net.msrandom.minecraftcodev.core

import net.msrandom.minecraftcodev.core.dependency.MinecraftDependency
import net.msrandom.minecraftcodev.core.dependency.MinecraftDependencyImpl
import org.gradle.api.internal.artifacts.dsl.CapabilityNotationParserFactory
import org.gradle.api.internal.attributes.ImmutableAttributesFactory
import org.gradle.api.plugins.ExtensionAware

@Suppress("unused")
abstract class MinecraftCodevExtension(private val attributesFactory: ImmutableAttributesFactory) : ExtensionAware {
    private val capabilityNotationParser = CapabilityNotationParserFactory(false).create()!!

    operator fun invoke(name: Any, version: String?): MinecraftDependency =
        MinecraftDependencyImpl(name.toString(), version ?: "", null).apply {
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
}
