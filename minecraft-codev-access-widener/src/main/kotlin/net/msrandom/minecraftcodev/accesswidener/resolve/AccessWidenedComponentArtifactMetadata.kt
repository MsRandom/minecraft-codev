package net.msrandom.minecraftcodev.accesswidener.resolve

import org.gradle.api.Project
import org.gradle.internal.component.external.model.ModuleComponentArtifactIdentifier
import org.gradle.internal.component.external.model.ModuleComponentArtifactMetadata

class AccessWidenedComponentArtifactMetadata(
    val delegate: ModuleComponentArtifactMetadata,
    private val id: AccessWidenedComponentIdentifier,
    val namespace: String?,
    private val project: Project
) : ModuleComponentArtifactMetadata by delegate {
    override fun getId() = object : ModuleComponentArtifactIdentifier by delegate.id {
        override fun getComponentIdentifier() = id
    }

    override fun getComponentId() = id

/*    override fun getBuildDependencies(): TaskDependency = object : AbstractTaskDependency() {
        override fun visitDependencies(context: TaskDependencyResolveContext) {
            context.add(delegate.buildDependencies)
            project.addConfigurationResolutionDependencies(context, project.configurations.getByName(id.accessWidenersConfiguration))
        }
    }*/
}
