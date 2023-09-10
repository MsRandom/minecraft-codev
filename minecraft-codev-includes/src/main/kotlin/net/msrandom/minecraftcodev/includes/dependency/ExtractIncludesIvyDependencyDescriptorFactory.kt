package net.msrandom.minecraftcodev.includes.dependency

import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.attributes.AttributeContainer
import org.gradle.api.internal.artifacts.ivyservice.moduleconverter.dependencies.AbstractIvyDependencyDescriptorFactory
import org.gradle.api.internal.artifacts.ivyservice.moduleconverter.dependencies.DependencyDescriptorFactory
import org.gradle.api.internal.artifacts.ivyservice.moduleconverter.dependencies.ExcludeRuleConverter
import org.gradle.internal.component.model.LocalOriginDependencyMetadata
import javax.inject.Inject

open class ExtractIncludesIvyDependencyDescriptorFactory @Inject constructor(
    excludeRuleConverter: ExcludeRuleConverter,
    private val descriptorFactory: DependencyDescriptorFactory
) : AbstractIvyDependencyDescriptorFactory(excludeRuleConverter) {
    override fun createDependencyDescriptor(
        componentId: ComponentIdentifier,
        clientConfiguration: String?,
        attributes: AttributeContainer?,
        dependency: ModuleDependency
    ): LocalOriginDependencyMetadata {
        val source = (dependency as ExtractIncludesDependency<*>).sourceDependency
        val sourceDescriptor = descriptorFactory.createDependencyDescriptor(componentId, clientConfiguration, attributes, source)

        return DslOriginExtractIncludesDependencyMetadata(sourceDescriptor, dependency)
    }

    override fun canConvert(dependency: ModuleDependency) = dependency is ExtractIncludesDependency<*>
}
