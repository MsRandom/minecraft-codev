package net.msrandom.minecraftcodev.includes.resolve

import net.msrandom.minecraftcodev.core.MinecraftCodevExtension
import net.msrandom.minecraftcodev.core.resolve.PassthroughArtifactMetadata
import net.msrandom.minecraftcodev.core.utils.getAttribute
import net.msrandom.minecraftcodev.core.utils.zipFileSystem
import net.msrandom.minecraftcodev.gradle.api.*
import net.msrandom.minecraftcodev.includes.IncludesExtension
import org.gradle.api.Project
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.internal.artifacts.DefaultModuleIdentifier
import org.gradle.internal.DisplayName
import org.gradle.internal.component.external.model.DefaultModuleComponentSelector
import org.gradle.internal.component.external.model.GradleDependencyMetadata
import org.gradle.internal.component.external.model.ModuleComponentArtifactMetadata
import org.gradle.internal.component.model.ComponentArtifactMetadata
import org.gradle.internal.component.model.DependencyMetadata
import org.gradle.internal.component.model.IvyArtifactName
import javax.inject.Inject

open class ExtractIncludesComponentMetadataDelegate
@Inject
constructor(
    identifier: ExtractIncludesComponentIdentifier,
    private val overrideArtifact: IvyArtifactName?,
    private val project: Project,
) : DelegateComponentMetadataHolder<ExtractIncludesComponentIdentifier, ExtractIncludesComponentMetadataDelegate>(identifier) {
    override val type: Class<ExtractIncludesComponentMetadataDelegate>
        get() = ExtractIncludesComponentMetadataDelegate::class.java

    override fun wrapVariant(
        variant: VariantMetadataHolder,
        artifactProvider: ArtifactProvider,
    ): VariantMetadata {
        return if (variant.attributes.getAttribute(
                Category.CATEGORY_ATTRIBUTE.name,
            ) == Category.LIBRARY && variant.attributes.getAttribute(
                LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE.name,
            ) == LibraryElements.JAR
        ) {
            val includeRules =
                project.extensions.getByType(
                    MinecraftCodevExtension::class.java,
                ).extensions.getByType(IncludesExtension::class.java).rules

            val extraDependencies = mutableListOf<DependencyMetadata>()
            val extractIncludes = hashSetOf<ComponentArtifactMetadata>()

            // Expensive operations ahead:
            //  We need to either add the included Jars as dependencies, or as artifacts
            //  But... this is metadata resolution, we do not have the artifact, so we can't tell what included Jars there is
            //  Hence, we need to resolve the artifact early(yay), which is why metadata fetching is marked as expensive for this resolver
            //  Another side effect is that this resolver can not be transitive, as that would require transitively resolving artifacts which... yea
            //  So here we go, resolving artifacts early

            for (artifact in variant.artifacts) {
                if (artifact is ModuleComponentArtifactMetadata && artifact.name.type == ArtifactTypeDefinition.JAR_TYPE) {
                    val result = artifactProvider.apply(artifact, overrideArtifact)

                    if (!result.hasResult()) continue

                    val failure = result.failure

                    if (failure != null) {
                        if (artifact.isOptionalArtifact) {
                            continue
                        }

                        throw failure
                    }

                    val included =
                        zipFileSystem(result.result.file.toPath()).use {
                            val root = it.base.getPath("/")

                            val handler =
                                includeRules.get().firstNotNullOfOrNull { rule ->
                                    rule.load(root)
                                } ?: return@use emptyList()

                            handler.list(root)
                        }

                    if (included.isEmpty()) {
                        continue
                    }

                    extractIncludes.add(artifact)

                    for (include in included) {
                        val (_, group, module, version) = include

                        if (group != null && module != null && version != null) {
                            extraDependencies.add(
                                GradleDependencyMetadata(
                                    DefaultModuleComponentSelector.newSelector(
                                        DefaultModuleIdentifier.newId(group, module),
                                        version,
                                    ),
                                    emptyList(),
                                    false,
                                    false,
                                    null,
                                    false,
                                    null,
                                ),
                            )
                        }
                    }
                }
            }

            if (extraDependencies.isEmpty()) {
                return variant
            }

            return WrappedVariantMetadata(
                variant.name,
                id,
                variant.attributes,
                object : DisplayName {
                    override fun getDisplayName() = "includes extracted ${variant.displayName.displayName}"

                    override fun getCapitalizedDisplayName() = "Includes Extracted ${variant.displayName.capitalizedDisplayName}"
                },
                variant.dependencies + extraDependencies,
                variant.artifacts.map {
                    if (it.name.type == ArtifactTypeDefinition.JAR_TYPE && it in extractIncludes) {
                        ExtractIncludesComponentArtifactMetadata(it as ModuleComponentArtifactMetadata, id)
                    } else {
                        PassthroughArtifactMetadata(it)
                    }
                },
            )
        } else {
            variant
        }
    }
}
