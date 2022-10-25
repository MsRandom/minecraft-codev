package net.msrandom.minecraftcodev.accesswidener

import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.attributes.AttributeContainer
import org.gradle.api.internal.artifacts.ivyservice.moduleconverter.dependencies.AbstractIvyDependencyDescriptorFactory
import org.gradle.api.internal.artifacts.ivyservice.moduleconverter.dependencies.DependencyDescriptorFactory
import org.gradle.api.internal.artifacts.ivyservice.moduleconverter.dependencies.ExcludeRuleConverter
import org.gradle.internal.component.model.LocalOriginDependencyMetadata
import javax.inject.Inject

open class AccessWidenedIvyDependencyDescriptorFactory @Inject constructor(
    excludeRuleConverter: ExcludeRuleConverter,
    private val descriptorFactory: DependencyDescriptorFactory
) : AbstractIvyDependencyDescriptorFactory(excludeRuleConverter) {
    override fun createDependencyDescriptor(
        componentId: ComponentIdentifier,
        clientConfiguration: String?,
        attributes: AttributeContainer?,
        dependency: ModuleDependency
    ): LocalOriginDependencyMetadata {
        val source = (dependency as AccessWidenedDependency<*>).sourceDependency
        val sourceDescriptor = descriptorFactory.createDependencyDescriptor(componentId, clientConfiguration, attributes, source)

        return DslOriginAccessWidenedDependencyMetadata(
            sourceDescriptor,
            dependency,
            dependency.accessWidenersConfiguration
        )
    }

    override fun canConvert(dependency: ModuleDependency) = dependency is AccessWidenedDependency<*>
}
