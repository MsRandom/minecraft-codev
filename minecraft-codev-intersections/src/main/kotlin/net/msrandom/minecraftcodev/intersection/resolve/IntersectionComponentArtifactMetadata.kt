package net.msrandom.minecraftcodev.intersection.resolve

import org.gradle.api.Project
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.internal.artifacts.DefaultArtifactIdentifier
import org.gradle.api.internal.tasks.TaskDependencyContainerInternal
import org.gradle.api.internal.tasks.TaskDependencyFactory
import org.gradle.configurationcache.extensions.serviceOf
import org.gradle.internal.component.external.model.DefaultModuleComponentArtifactIdentifier
import org.gradle.internal.component.external.model.ModuleComponentArtifactMetadata
import org.gradle.internal.component.model.ComponentArtifactMetadata
import org.gradle.internal.component.model.DefaultIvyArtifactName

class IntersectionComponentArtifactMetadata(
    val artifacts: List<ComponentArtifactMetadata>,
    private val id: IntersectionComponentIdentifier,
    private val project: Project,
) : ModuleComponentArtifactMetadata {
    override fun getId() = DefaultModuleComponentArtifactIdentifier(id, name)

    override fun getComponentId() = id

    override fun getName() = DefaultIvyArtifactName(id.module, ArtifactTypeDefinition.JAR_TYPE, ArtifactTypeDefinition.JAR_TYPE)

    override fun getBuildDependencies(): TaskDependencyContainerInternal =
        project.serviceOf<TaskDependencyFactory>().visitingDependencies {
            for (artifact in artifacts) {
                it.add(artifact.buildDependencies)
            }
        }

    override fun toArtifactIdentifier() = DefaultArtifactIdentifier(getId())
}
