package net.msrandom.minecraftcodev.forge.dependency

import net.msrandom.minecraftcodev.core.dependency.DependencyFactory
import net.msrandom.minecraftcodev.core.dependency.MinecraftDependencyImpl
import org.gradle.api.Project
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.attributes.AttributeContainer
import org.gradle.api.internal.artifacts.DefaultModuleIdentifier
import org.gradle.api.internal.artifacts.VersionConstraintInternal
import org.gradle.api.internal.artifacts.ivyservice.moduleconverter.dependencies.AbstractIvyDependencyDescriptorFactory
import org.gradle.api.internal.artifacts.ivyservice.moduleconverter.dependencies.ExcludeRuleConverter
import org.gradle.internal.component.external.model.DefaultModuleComponentSelector
import org.gradle.internal.component.model.DependencyMetadata
import org.gradle.internal.component.model.LocalComponentDependencyMetadata
import org.gradle.internal.component.model.LocalOriginDependencyMetadata
import javax.inject.Inject

open class PatchedMinecraftIvyDependencyDescriptorFactory @Inject constructor(excludeRuleConverter: ExcludeRuleConverter) :
    AbstractIvyDependencyDescriptorFactory(excludeRuleConverter) {
    override fun createDependencyDescriptor(
        componentId: ComponentIdentifier,
        clientConfiguration: String?,
        attributes: AttributeContainer?,
        dependency: ModuleDependency
    ): LocalOriginDependencyMetadata {
        val externalModuleDependency = (dependency as PatchedMinecraftDependency).minecraftDependency
        val force = externalModuleDependency.isForce
        val changing = externalModuleDependency.isChanging
        val transitive = externalModuleDependency.isTransitive

        val selector = DefaultModuleComponentSelector.newSelector(
            DefaultModuleIdentifier.newId(dependency.group.orEmpty(), dependency.name),
            (externalModuleDependency.versionConstraint as VersionConstraintInternal).asImmutable(),
            dependency.getAttributes(),
            dependency.getRequestedCapabilities()
        )

        val excludes = convertExcludeRules(dependency.excludeRules)

        val dependencyMetaData = LocalComponentDependencyMetadata(
            componentId,
            selector,
            clientConfiguration,
            attributes,
            dependency.attributes,
            dependency.targetConfiguration,
            convertArtifacts(dependency.artifacts),
            excludes,
            force,
            changing,
            transitive,
            false,
            dependency.isEndorsingStrictVersions,
            dependency.reason
        )

        return DslOriginPatchedMinecraftDependencyMetadata(dependencyMetaData, dependency, dependency.patches)
    }

    override fun canConvert(dependency: ModuleDependency) =
        dependency is PatchedMinecraftDependency
}

open class PatchedMinecraftDependencyFactory : DependencyFactory {
    override fun createDependency(project: Project, descriptor: DependencyMetadata): ModuleDependency {
        descriptor as PatchedMinecraftDependencyMetadata

        val configuration = (descriptor as? LocalOriginDependencyMetadata)?.dependencyConfiguration

        val selector = descriptor.selector as ModuleComponentSelector
        return PatchedMinecraftDependency(MinecraftDependencyImpl(selector.module, selector.version, configuration).apply {
            version { versionConstraint ->
                selector.versionConstraint.requiredVersion.takeUnless(String::isEmpty)?.let(versionConstraint::require)
                selector.versionConstraint.preferredVersion.takeUnless(String::isEmpty)?.let(versionConstraint::require)
                selector.versionConstraint.rejectedVersions.takeUnless(List<*>::isEmpty)?.let { versionConstraint.reject(*it.toTypedArray()) }
                selector.versionConstraint.strictVersion.takeUnless(String::isEmpty)?.let(versionConstraint::require)
            }

            // TODO copy more data here
        }, descriptor.relatedConfiguration)
    }

    override fun canConvert(descriptor: DependencyMetadata) = descriptor is PatchedMinecraftDependencyMetadata
}

class PatchedComponentIdentifier(val moduleComponentIdentifier: ModuleComponentIdentifier, val patches: String, val moduleConfiguration: String?) : ModuleComponentIdentifier by moduleComponentIdentifier {
    override fun getDisplayName() = "Patched ${moduleComponentIdentifier.displayName}"
}
