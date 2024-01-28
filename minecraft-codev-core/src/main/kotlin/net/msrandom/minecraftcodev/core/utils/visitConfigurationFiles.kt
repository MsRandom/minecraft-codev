package net.msrandom.minecraftcodev.core.utils

import net.msrandom.minecraftcodev.core.resolve.ComponentResolversChainProvider
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.internal.artifacts.ResolveContext
import org.gradle.api.internal.artifacts.dependencies.DefaultImmutableVersionConstraint
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.UnionVersionSelector
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionSelectorScheme
import org.gradle.api.internal.attributes.AttributeContainerInternal
import org.gradle.api.internal.attributes.AttributesSchemaInternal
import org.gradle.configurationcache.extensions.serviceOf
import org.gradle.internal.component.local.model.DslOriginDependencyMetadata
import org.gradle.internal.component.local.model.LocalConfigurationMetadata
import org.gradle.internal.component.model.ComponentArtifactMetadata
import org.gradle.internal.component.model.DefaultComponentOverrideMetadata
import org.gradle.internal.component.model.DependencyMetadata
import org.gradle.internal.resolve.result.DefaultBuildableArtifactResolveResult
import org.gradle.internal.resolve.result.DefaultBuildableComponentIdResolveResult
import org.gradle.internal.resolve.result.DefaultBuildableComponentResolveResult
import java.io.File

// TODO Follow DefaultConfigurationResolver more to have more parity with regular resolution.
fun Project.visitConfigurationFiles(resolvers: ComponentResolversChainProvider, configuration: Configuration, source: Dependency? = null, visit: (File) -> Unit) {
    val context = configuration as ResolveContext

    val localConfigurationMetadata = context.toRootComponentMetaData().getConfiguration(context.name) as LocalConfigurationMetadata

    for (fileMetadata in localConfigurationMetadata.files) {
        if (source == null || fileMetadata.source == source) {
            for (file in fileMetadata.files) {
                visit(file)
            }
        }
    }

    for (dependency in localConfigurationMetadata.dependencies) {
        visitDependencyArtifacts(resolvers, configuration, dependency, false, source, visit)
    }
}

fun Project.visitDependencyArtifacts(resolvers: ComponentResolversChainProvider, configuration: Configuration, dependency: DependencyMetadata, transitive: Boolean, source: Dependency? = null, visit: (File) -> Unit) {
    val versionSelectorSchema = serviceOf<VersionSelectorScheme>()

    if (source == null || dependency is DslOriginDependencyMetadata && dependency.source == source) {
        val selector = dependency.selector
        val constraint = if (selector is ModuleComponentSelector) selector.versionConstraint else DefaultImmutableVersionConstraint.of()
        val strictly = if (constraint.strictVersion.isEmpty()) null else versionSelectorSchema.parseSelector(constraint.strictVersion)
        val require = if (constraint.requiredVersion.isEmpty()) null else versionSelectorSchema.parseSelector(constraint.requiredVersion)
        val preferred = if (constraint.preferredVersion.isEmpty()) null else versionSelectorSchema.parseSelector(constraint.preferredVersion)
        val reject = UnionVersionSelector(constraint.rejectedVersions.map(versionSelectorSchema::parseSelector))
        val idResult = DefaultBuildableComponentIdResolveResult()

        resolvers.get().componentIdResolver.resolve(dependency, strictly ?: preferred ?: require, reject, idResult)

        val metadata = idResult.state ?: run {
            val componentResult = DefaultBuildableComponentResolveResult()

            resolvers.get().componentResolver.resolve(idResult.id, DefaultComponentOverrideMetadata.forDependency(dependency.isChanging, dependency.artifacts.getOrNull(0), DefaultComponentOverrideMetadata.extractClientModule(dependency)), componentResult)

            componentResult.state
        }

        for (selectedConfiguration in dependency.selectVariants(
            (configuration.attributes as AttributeContainerInternal).asImmutable(),
            metadata,
            project.dependencies.attributesSchema as AttributesSchemaInternal,
            configuration.outgoing.capabilities
        ).variants) {
            fun resolve(artifact: ComponentArtifactMetadata) {
                val artifactResult = DefaultBuildableArtifactResolveResult()
                resolvers.get().artifactResolver.resolveArtifact(metadata.prepareForArtifactResolution().resolveMetadata, artifact, artifactResult)

                visit(artifactResult.result.file)
            }

            metadata.resolveArtifactsFor(selectedConfiguration).artifacts.forEach(::resolve)

            if (!transitive) {
                continue
            }

            for (transitiveDependency in selectedConfiguration.dependencies) {
                visitDependencyArtifacts(resolvers, configuration, transitiveDependency, true, source, visit)
            }
        }
    }
}
