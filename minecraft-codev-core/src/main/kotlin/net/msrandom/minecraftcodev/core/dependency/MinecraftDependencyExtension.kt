package net.msrandom.minecraftcodev.core.dependency

import org.gradle.api.internal.artifacts.dependencies.AbstractModuleDependency
import org.gradle.api.internal.artifacts.dsl.CapabilityNotationParserFactory
import org.gradle.api.internal.attributes.ImmutableAttributesFactory

// TODO remove somehow?
open class MinecraftDependencyExtension(private val attributesFactory: ImmutableAttributesFactory) {
    private val capabilityNotationParser = CapabilityNotationParserFactory(false).create()!!

    internal fun <T : AbstractModuleDependency> injectServices(dependency: T): T {
        dependency.setAttributesFactory(attributesFactory)
        dependency.setCapabilityNotationParser(capabilityNotationParser)

        return dependency
    }
}
