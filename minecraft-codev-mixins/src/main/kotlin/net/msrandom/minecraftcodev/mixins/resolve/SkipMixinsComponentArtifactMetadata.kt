package net.msrandom.minecraftcodev.mixins.resolve

import org.gradle.api.Project
import org.gradle.api.internal.tasks.TaskDependencyContainerInternal
import org.gradle.api.internal.tasks.TaskDependencyFactory
import org.gradle.configurationcache.extensions.serviceOf
import org.gradle.internal.component.external.model.DefaultModuleComponentArtifactIdentifier
import org.gradle.internal.component.external.model.ModuleComponentArtifactIdentifier
import org.gradle.internal.component.external.model.ModuleComponentArtifactMetadata
import org.gradle.internal.component.external.model.ModuleComponentFileArtifactIdentifier

class MixinComponentArtifactMetadata(val delegate: ModuleComponentArtifactMetadata, private val id: MixinComponentIdentifier, val project: Project) : ModuleComponentArtifactMetadata by delegate {
    override fun getId(): ModuleComponentArtifactIdentifier =
        delegate.id.let {
            if (it is DefaultModuleComponentArtifactIdentifier) {
                DefaultModuleComponentArtifactIdentifier(id, it.name)
            } else {
                ModuleComponentFileArtifactIdentifier(id, delegate.id.fileName)
            }
        }

    override fun getComponentId() = id

    override fun getBuildDependencies(): TaskDependencyContainerInternal =
        project.serviceOf<TaskDependencyFactory>().visitingDependencies {
            it.add(delegate.buildDependencies)
            it.add(project.configurations.getByName(id.mixinsConfiguration))
        }
}

class SkipMixinsComponentArtifactMetadata(val delegate: ModuleComponentArtifactMetadata, private val id: SkipMixinsComponentIdentifier) : ModuleComponentArtifactMetadata by delegate {
    override fun getId(): ModuleComponentArtifactIdentifier =
        delegate.id.let {
            if (it is DefaultModuleComponentArtifactIdentifier) {
                DefaultModuleComponentArtifactIdentifier(id, it.name)
            } else {
                ModuleComponentFileArtifactIdentifier(id, delegate.id.fileName)
            }
        }

    override fun getComponentId() = id
}
