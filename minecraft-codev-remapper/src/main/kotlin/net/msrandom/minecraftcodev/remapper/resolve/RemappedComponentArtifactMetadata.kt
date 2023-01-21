package net.msrandom.minecraftcodev.remapper.resolve

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.internal.artifacts.DefaultArtifactIdentifier
import org.gradle.api.internal.artifacts.DefaultModuleVersionIdentifier
import org.gradle.api.internal.tasks.DefaultTaskDependency
import org.gradle.api.tasks.TaskDependency
import org.gradle.internal.component.external.model.DefaultModuleComponentArtifactIdentifier
import org.gradle.internal.component.external.model.ModuleComponentArtifactIdentifier
import org.gradle.internal.component.external.model.ModuleComponentArtifactMetadata
import org.gradle.internal.component.external.model.ModuleComponentFileArtifactIdentifier
import org.gradle.internal.component.model.DefaultIvyArtifactName

sealed interface RemapperArtifact {
    val mappingsConfiguration: String
}

class MappingsArtifact(private val componentIdentifier: ModuleComponentIdentifier, override val mappingsConfiguration: String, private val project: Project) :
    RemapperArtifact,
    ModuleComponentArtifactMetadata {
    override fun getId() = DefaultModuleComponentArtifactIdentifier(componentIdentifier, name)
    override fun getComponentId() = componentIdentifier
    override fun getName() = DefaultIvyArtifactName("mappings", ArtifactTypeDefinition.ZIP_TYPE, ArtifactTypeDefinition.ZIP_TYPE)

    override fun getBuildDependencies(): TaskDependency = DefaultTaskDependency.EMPTY
/*    override fun getBuildDependencies(): TaskDependency = object : AbstractTaskDependency() {
        override fun visitDependencies(context: TaskDependencyResolveContext) {
            project.addConfigurationResolutionDependencies(context, project.configurations.getByName(mappingsConfiguration))
        }
    }*/

    override fun toArtifactIdentifier() = DefaultArtifactIdentifier(
        DefaultModuleVersionIdentifier.newId(
            componentIdentifier.moduleIdentifier,
            componentIdentifier.version
        ),
        name.name,
        name.type,
        name.extension,
        name.classifier
    )
}

class RemappedComponentArtifactMetadata(
    val delegate: ModuleComponentArtifactMetadata,
    private val id: RemappedComponentIdentifier,
    val namespace: String?,
    val classpath: Configuration,
    private val project: Project
) : RemapperArtifact, ModuleComponentArtifactMetadata by delegate {
    override val mappingsConfiguration
        get() = id.mappingsConfiguration

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
            project.addConfigurationResolutionDependencies(context, classpath)
            project.addConfigurationResolutionDependencies(context, project.configurations.getByName(id.mappingsConfiguration))
        }
    }*/
}
