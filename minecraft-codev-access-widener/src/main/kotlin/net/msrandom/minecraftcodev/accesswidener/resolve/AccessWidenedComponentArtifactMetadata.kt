package net.msrandom.minecraftcodev.accesswidener.resolve

import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin.Companion.addConfigurationResolutionDependencies
import org.gradle.api.Project
import org.gradle.api.internal.tasks.AbstractTaskDependency
import org.gradle.api.internal.tasks.TaskDependencyResolveContext
import org.gradle.api.tasks.TaskDependency
import org.gradle.internal.component.external.model.ModuleComponentArtifactMetadata

class AccessWidenedComponentArtifactMetadata(
    val delegate: ModuleComponentArtifactMetadata,
    private val id: AccessWidenedComponentIdentifier,
    val namespace: String?,
    private val project: Project
) : ModuleComponentArtifactMetadata by delegate {
    override fun getComponentId() = id

    override fun getBuildDependencies(): TaskDependency = object : AbstractTaskDependency() {
        override fun visitDependencies(context: TaskDependencyResolveContext) {
            context.add(delegate.buildDependencies)
            project.addConfigurationResolutionDependencies(context, project.configurations.getByName(id.accessWidenersConfiguration))
        }
    }
}
