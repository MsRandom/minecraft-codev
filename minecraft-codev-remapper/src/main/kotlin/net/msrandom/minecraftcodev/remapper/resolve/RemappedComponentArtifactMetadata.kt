package net.msrandom.minecraftcodev.remapper.resolve

import org.gradle.api.Project
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.internal.artifacts.DefaultArtifactIdentifier
import org.gradle.api.internal.artifacts.DefaultModuleVersionIdentifier
import org.gradle.api.internal.tasks.AbstractTaskDependency
import org.gradle.api.internal.tasks.TaskDependencyInternal
import org.gradle.api.internal.tasks.TaskDependencyResolveContext
import org.gradle.api.tasks.TaskDependency
import org.gradle.internal.component.external.model.DefaultModuleComponentArtifactIdentifier
import org.gradle.internal.component.external.model.ModuleComponentArtifactIdentifier
import org.gradle.internal.component.external.model.ModuleComponentArtifactMetadata
import org.gradle.internal.component.external.model.ModuleComponentFileArtifactIdentifier
import org.gradle.internal.component.model.ComponentArtifactMetadata
import org.gradle.internal.component.model.DefaultIvyArtifactName
import org.gradle.internal.component.model.ModuleSources

sealed interface RemapperArtifact {
    val mappingsConfiguration: String
}

class MappingsArtifact(
    val project: Project,
    private val componentIdentifier: ModuleComponentIdentifier,
    override val mappingsConfiguration: String
) :
    RemapperArtifact,
    ModuleComponentArtifactMetadata {
    override fun getId() = DefaultModuleComponentArtifactIdentifier(componentIdentifier, name)
    override fun getComponentId() = componentIdentifier
    override fun getName() = DefaultIvyArtifactName(componentIdentifier.module, ArtifactTypeDefinition.ZIP_TYPE, ArtifactTypeDefinition.ZIP_TYPE, "mappings")

    override fun getBuildDependencies() = object : AbstractTaskDependency() {
        override fun visitDependencies(context: TaskDependencyResolveContext) {
            context.add(project.configurations.getByName(mappingsConfiguration))
        }
    }

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
    val project: Project,
    val delegate: ModuleComponentArtifactMetadata,
    private val id: RemappedComponentIdentifier,
    val namespace: String?,
    val selectedArtifacts: List<Pair<ModuleSources, List<ComponentArtifactMetadata>>>
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

    override fun isOptionalArtifact() = delegate.isOptionalArtifact


    override fun getBuildDependencies() = object : AbstractTaskDependency() {
        override fun visitDependencies(context: TaskDependencyResolveContext) {
            context.add(delegate.buildDependencies)
            context.add(project.configurations.getByName(id.mappingsConfiguration))
        }
    }
}

class PassthroughRemappedArtifactMetadata(val original: ComponentArtifactMetadata): ComponentArtifactMetadata by original
