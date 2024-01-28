package net.msrandom.minecraftcodev.accesswidener.resolve.intersection

import org.gradle.api.Project
import org.gradle.api.artifacts.ArtifactIdentifier
import org.gradle.api.artifacts.ModuleVersionIdentifier
import org.gradle.api.internal.artifacts.DefaultArtifactIdentifier
import org.gradle.api.internal.tasks.AbstractTaskDependency
import org.gradle.api.internal.tasks.TaskDependencyResolveContext
import org.gradle.internal.component.external.model.DefaultModuleComponentArtifactIdentifier
import org.gradle.internal.component.external.model.ModuleComponentArtifactIdentifier
import org.gradle.internal.component.external.model.ModuleComponentArtifactMetadata
import org.gradle.internal.component.external.model.ModuleComponentFileArtifactIdentifier
import org.gradle.internal.component.model.ComponentArtifactMetadata
import org.gradle.internal.component.model.DefaultIvyArtifactName
import org.gradle.internal.component.model.IvyArtifactName
import org.gradle.internal.component.model.ModuleSources

class AccessModifierIntersectionComponentArtifactMetadata(
    val project: Project,
    val selectedArtifacts: List<List<Pair<ModuleSources, ComponentArtifactMetadata>>>,
    private val id: AccessModifierIntersectionComponentIdentifier,
    private val moduleVersionId: ModuleVersionIdentifier,
) : ModuleComponentArtifactMetadata {
    override fun getId(): ModuleComponentArtifactIdentifier =
        DefaultModuleComponentArtifactIdentifier(id, name)

    override fun getComponentId() = id

    override fun getName() = DefaultIvyArtifactName(id.module, "json", "json")

    override fun getBuildDependencies() = object : AbstractTaskDependency() {
        override fun visitDependencies(context: TaskDependencyResolveContext) {
            for (part in selectedArtifacts) {
                for ((_, artifact) in part) {
                    context.add(artifact.buildDependencies)
                }
            }
        }
    }

    override fun toArtifactIdentifier() = DefaultArtifactIdentifier(
        moduleVersionId,
        name.name,
        name.type,
        name.extension,
        name.classifier
    )
}
