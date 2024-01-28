package net.msrandom.minecraftcodev.core.resolve

import org.gradle.api.internal.artifacts.ivyservice.moduleconverter.dependencies.DependencyMetadataConverter

fun interface IvyDependencyDescriptorFactoriesProvider {
    fun get(): List<DependencyMetadataConverter>
}
