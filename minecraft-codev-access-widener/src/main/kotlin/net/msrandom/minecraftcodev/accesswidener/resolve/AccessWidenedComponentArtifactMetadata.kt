package net.msrandom.minecraftcodev.accesswidener.resolve

import org.gradle.api.Project
import org.gradle.internal.component.external.model.DefaultModuleComponentArtifactIdentifier
import org.gradle.internal.component.external.model.ModuleComponentArtifactIdentifier
import org.gradle.internal.component.external.model.ModuleComponentArtifactMetadata
import org.gradle.internal.component.external.model.ModuleComponentFileArtifactIdentifier

class AccessWidenedComponentArtifactMetadata(
    val delegate: ModuleComponentArtifactMetadata,
    private val id: AccessWidenedComponentIdentifier,
    val namespace: String?,
    private val project: Project
) : ModuleComponentArtifactMetadata by delegate {
    override fun getId(): ModuleComponentArtifactIdentifier = delegate.id.let {
        if (it is DefaultModuleComponentArtifactIdentifier) {
            DefaultModuleComponentArtifactIdentifier(id, it.name)
        } else {
            ModuleComponentFileArtifactIdentifier(id, delegate.id.fileName)
        }
    }

    override fun getComponentId() = id

/*    override fun getBuildDependencies(): TaskDependency = object : AbstractTaskDependency() {
        override fun visitDependencies(context: TaskDependencyResolveContext) {
            context.add(delegate.buildDependencies)
            project.addConfigurationResolutionDependencies(context, project.configurations.getByName(id.accessWidenersConfiguration))
        }
    }*/
}
