package net.msrandom.minecraftcodev.remapper.dependency

import net.msrandom.minecraftcodev.core.utils.named
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.attributes.AttributeContainer
import org.gradle.api.internal.artifacts.ivyservice.moduleconverter.dependencies.AbstractDependencyMetadataConverter
import org.gradle.api.internal.artifacts.ivyservice.moduleconverter.dependencies.DependencyMetadataFactory
import org.gradle.api.internal.artifacts.ivyservice.moduleconverter.dependencies.ExcludeRuleConverter
import org.gradle.api.model.ObjectFactory
import org.gradle.internal.component.model.LocalOriginDependencyMetadata
import javax.inject.Inject

open class RemappedDependencyMetadataConverter @Inject constructor(
    excludeRuleConverter: ExcludeRuleConverter,
    private val objects: ObjectFactory,
    private val descriptorFactory: DependencyMetadataFactory
) : AbstractDependencyMetadataConverter(excludeRuleConverter) {
    override fun createDependencyMetadata(
        componentId: ComponentIdentifier,
        clientConfiguration: String?,
        attributes: AttributeContainer?,
        dependency: ModuleDependency
    ): LocalOriginDependencyMetadata {
        val source = (dependency as RemappedDependency<*>).sourceDependency
        val sourceDescriptor = descriptorFactory.createDependencyMetadata(componentId, clientConfiguration, attributes, source)

        return DslOriginRemappedDependencyMetadata(
            sourceDescriptor,
            dependency,
            dependency.sourceNamespace?.let(objects::named),
            objects.named(dependency.targetNamespace),
            dependency.mappingsConfiguration
        )
    }

    override fun canConvert(dependency: ModuleDependency) = dependency is RemappedDependency<*>
}
