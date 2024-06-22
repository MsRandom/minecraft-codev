package net.msrandom.minecraftcodev.remapper.resolve

import org.gradle.api.Project
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.internal.artifacts.DefaultArtifactIdentifier
import org.gradle.api.internal.artifacts.DefaultModuleVersionIdentifier
import org.gradle.api.internal.tasks.TaskDependencyContainerInternal
import org.gradle.api.internal.tasks.TaskDependencyFactory
import org.gradle.api.tasks.TaskDependency
import org.gradle.configurationcache.extensions.serviceOf
import org.gradle.internal.component.external.model.DefaultModuleComponentArtifactIdentifier
import org.gradle.internal.component.external.model.ModuleComponentArtifactIdentifier
import org.gradle.internal.component.external.model.ModuleComponentArtifactMetadata
import org.gradle.internal.component.external.model.ModuleComponentFileArtifactIdentifier
import org.gradle.internal.component.model.DefaultIvyArtifactName

sealed interface RemapperArtifact {
    val mappingsConfiguration: String
}

open class MappingsArtifact(
    private val componentIdentifier: RemappedComponentIdentifier,
    override val mappingsConfiguration: String,
    val project: Project,
) : RemapperArtifact, ModuleComponentArtifactMetadata {
    override fun getId() = DefaultModuleComponentArtifactIdentifier(componentIdentifier, name)

    override fun getComponentId() = componentIdentifier

    override fun getName() =
        DefaultIvyArtifactName(
            componentIdentifier.module,
            ArtifactTypeDefinition.ZIP_TYPE,
            ArtifactTypeDefinition.ZIP_TYPE,
            "mappings",
        )

    override fun getBuildDependencies(): TaskDependencyContainerInternal =
        project.serviceOf<TaskDependencyFactory>().visitingDependencies {
            it.add(project.configurations.getByName(mappingsConfiguration))
        }

    override fun toArtifactIdentifier() =
        DefaultArtifactIdentifier(
            DefaultModuleVersionIdentifier.newId(
                componentIdentifier.moduleIdentifier,
                componentIdentifier.version,
            ),
            name.name,
            name.type,
            name.extension,
            name.classifier,
        )
}

open class RemappedComponentArtifactMetadata(
    val delegate: ModuleComponentArtifactMetadata,
    private val id: RemappedComponentIdentifier,
    val namespace: String,
    val project: Project,
) : RemapperArtifact, ModuleComponentArtifactMetadata by delegate {
    override val mappingsConfiguration
        get() = id.mappingsConfiguration

    override fun getId(): ModuleComponentArtifactIdentifier =
        delegate.id.let {
            if (it is DefaultModuleComponentArtifactIdentifier) {
                DefaultModuleComponentArtifactIdentifier(id, it.name)
            } else {
                ModuleComponentFileArtifactIdentifier(id, delegate.id.fileName)
            }
        }

    override fun getComponentId() = id

    override fun isOptionalArtifact() = delegate.isOptionalArtifact

    override fun getBuildDependencies(): TaskDependency =
        project.serviceOf<TaskDependencyFactory>().visitingDependencies {
            it.add(delegate.buildDependencies)
            it.add(project.configurations.getByName(id.mappingsConfiguration))
        }
}
