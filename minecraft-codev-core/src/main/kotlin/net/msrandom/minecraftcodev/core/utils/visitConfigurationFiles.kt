package net.msrandom.minecraftcodev.core.utils

import net.msrandom.minecraftcodev.core.resolve.ComponentResolversChainProvider
import net.msrandom.minecraftcodev.gradle.CodevGradleLinkageLoader.allArtifacts
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.internal.artifacts.ConfigurationResolver
import org.gradle.api.internal.artifacts.DefaultResolverResults
import org.gradle.api.internal.artifacts.ResolveContext
import org.gradle.api.internal.artifacts.dependencies.DefaultImmutableVersionConstraint
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.UnionVersionSelector
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionSelectorScheme
import org.gradle.api.internal.attributes.AttributeContainerInternal
import org.gradle.api.internal.attributes.AttributesSchemaInternal
import org.gradle.api.specs.Spec
import org.gradle.configurationcache.extensions.serviceOf
import org.gradle.internal.component.local.model.LocalConfigurationMetadata
import org.gradle.internal.component.model.DefaultComponentOverrideMetadata
import org.gradle.internal.resolve.result.DefaultBuildableArtifactResolveResult
import org.gradle.internal.resolve.result.DefaultBuildableComponentIdResolveResult
import org.gradle.internal.resolve.result.DefaultBuildableComponentResolveResult
import java.io.File

// FIXME: This is broken, attempts to acquire lock.
fun Project.visitConfigurationFiles(resolvers: ComponentResolversChainProvider, configuration: Configuration, specs: List<Spec<in Dependency>> = emptyList(), visit: (File) -> Unit) {
    val results = DefaultResolverResults()
    val configurationResolver = serviceOf<ConfigurationResolver>()
    val versionSelectorSchema = serviceOf<VersionSelectorScheme>()
    val context = configuration as ResolveContext

    val localConfigurationMetadata = context.toRootComponentMetaData().getConfiguration(context.name) as LocalConfigurationMetadata

    for (fileMetadata in localConfigurationMetadata.files) {
        for (file in fileMetadata.files) {
            visit(file)
        }
    }

    for (dependency in localConfigurationMetadata.dependencies) {
        val selector = dependency.selector
        val constraint = if (selector is ModuleComponentSelector) selector.versionConstraint else DefaultImmutableVersionConstraint.of()
        val require = if (constraint.requiredVersion.isEmpty()) null else versionSelectorSchema.parseSelector(constraint.requiredVersion)
        val preferred = if (constraint.preferredVersion.isEmpty()) null else versionSelectorSchema.parseSelector(constraint.preferredVersion)
        val reject = UnionVersionSelector(constraint.rejectedVersions.map(versionSelectorSchema::parseSelector))
        val idResult = DefaultBuildableComponentIdResolveResult()

        resolvers.get().componentIdResolver.resolve(dependency, preferred ?: require, reject, idResult)

        val metadata = idResult.metadata ?: run {
            val componentResult = DefaultBuildableComponentResolveResult()
            resolvers.get().componentResolver.resolve(idResult.id, DefaultComponentOverrideMetadata.EMPTY, componentResult)

            componentResult.metadata
        }

        for (selectedConfiguration in dependency.selectConfigurations(
            (context.attributes as AttributeContainerInternal).asImmutable(),
            metadata,
            project.dependencies.attributesSchema as AttributesSchemaInternal,
            configuration.outgoing.capabilities
        )) {
            if (dependency.artifacts.isEmpty()) {
                for (artifact in selectedConfiguration.allArtifacts) {
                    val artifactResult = DefaultBuildableArtifactResolveResult()
                    resolvers.get().artifactResolver.resolveArtifact(artifact, metadata.sources, artifactResult)

                    visit(artifactResult.result)
                }
            } else {
                for (artifactName in dependency.artifacts) {
                    val artifact = selectedConfiguration.artifact(artifactName)
                    val artifactResult = DefaultBuildableArtifactResolveResult()
                    resolvers.get().artifactResolver.resolveArtifact(artifact, metadata.sources, artifactResult)

                    visit(artifactResult.result)
                }
            }
        }
    }
/*
    configurationResolver.resolveGraph(configuration as ConfigurationInternal, results)
    configurationResolver.resolveArtifacts(configuration, results)

    if (specs.isEmpty()) {
        results.resolvedConfiguration.lenientConfiguration.artifacts
        results.visitedArtifacts.select(Specs.satisfyAll(), configuration.attributes, Specs.satisfyAll(), false).visitArtifacts(object : ArtifactVisitor {
            override fun visitArtifact(variantName: DisplayName, variantAttributes: AttributeContainer, capabilities: MutableList<out Capability>, artifact: ResolvableArtifact) {
                visit(artifact.file)
            }

            override fun requireArtifactFiles() = true
            override fun visitFailure(failure: Throwable) = throw failure
        }, false)
    } else {
        for (spec in specs) {
            results.visitedArtifacts.select(spec, configuration.attributes, Specs.satisfyAll(), false).visitArtifacts(object : ArtifactVisitor {
                override fun visitArtifact(variantName: DisplayName, variantAttributes: AttributeContainer, capabilities: MutableList<out Capability>, artifact: ResolvableArtifact) {
                    visit(artifact.file)
                }

                override fun requireArtifactFiles() = true
                override fun visitFailure(failure: Throwable) = throw failure
            }, false)
        }
    }*/
}
