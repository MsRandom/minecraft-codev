package net.msrandom.minecraftcodev.accesswidener.resolve.accesswidened

import org.gradle.api.Project
import org.gradle.api.internal.tasks.AbstractTaskDependency
import org.gradle.api.internal.tasks.TaskDependencyResolveContext
import org.gradle.internal.component.external.model.DefaultModuleComponentArtifactIdentifier
import org.gradle.internal.component.external.model.ModuleComponentArtifactIdentifier
import org.gradle.internal.component.external.model.ModuleComponentArtifactMetadata
import org.gradle.internal.component.external.model.ModuleComponentFileArtifactIdentifier
import org.gradle.internal.component.model.ComponentArtifactMetadata

class PassthroughAccessWidenedArtifactMetadata(val original: ComponentArtifactMetadata): ComponentArtifactMetadata by original

class AccessWidenedComponentArtifactMetadata(
    val project: Project,
    val delegate: ModuleComponentArtifactMetadata,
    private val id: AccessWidenedComponentIdentifier,
    val namespace: String?
) : ModuleComponentArtifactMetadata by delegate {
    override fun getId(): ModuleComponentArtifactIdentifier = delegate.id.let {
        if (it is DefaultModuleComponentArtifactIdentifier) {
            DefaultModuleComponentArtifactIdentifier(id, it.name)
        } else {
            ModuleComponentFileArtifactIdentifier(id, delegate.id.fileName)
        }
    }

    override fun getComponentId() = id

    override fun getBuildDependencies() = object : AbstractTaskDependency() {
        override fun visitDependencies(context: TaskDependencyResolveContext) {
            context.add(delegate.buildDependencies)
            context.add(project.configurations.getByName(id.accessWidenersConfiguration))
        }
    }
}
