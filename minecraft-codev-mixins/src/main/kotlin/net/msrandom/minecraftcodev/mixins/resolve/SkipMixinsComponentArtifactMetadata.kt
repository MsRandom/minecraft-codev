package net.msrandom.minecraftcodev.mixins.resolve

import org.gradle.internal.component.external.model.DefaultModuleComponentArtifactIdentifier
import org.gradle.internal.component.external.model.ModuleComponentArtifactIdentifier
import org.gradle.internal.component.external.model.ModuleComponentArtifactMetadata
import org.gradle.internal.component.external.model.ModuleComponentFileArtifactIdentifier
import org.gradle.internal.component.model.ComponentArtifactMetadata

class PassthroughMixinArtifactMetadata(val original: ComponentArtifactMetadata) : ComponentArtifactMetadata by original

class PassthroughSkipMixinsArtifactMetadata(val original: ComponentArtifactMetadata) : ComponentArtifactMetadata by original

class MixinComponentArtifactMetadata(val delegate: ModuleComponentArtifactMetadata, private val id: MixinComponentIdentifier) : ModuleComponentArtifactMetadata by delegate {
    override fun getId(): ModuleComponentArtifactIdentifier = delegate.id.let {
        if (it is DefaultModuleComponentArtifactIdentifier) {
            DefaultModuleComponentArtifactIdentifier(id, it.name)
        } else {
            ModuleComponentFileArtifactIdentifier(id, delegate.id.fileName)
        }
    }

    override fun getComponentId() = id
}

class SkipMixinsComponentArtifactMetadata(val delegate: ModuleComponentArtifactMetadata, private val id: SkipMixinsComponentIdentifier) : ModuleComponentArtifactMetadata by delegate {
    override fun getId(): ModuleComponentArtifactIdentifier = delegate.id.let {
        if (it is DefaultModuleComponentArtifactIdentifier) {
            DefaultModuleComponentArtifactIdentifier(id, it.name)
        } else {
            ModuleComponentFileArtifactIdentifier(id, delegate.id.fileName)
        }
    }

    override fun getComponentId() = id
}
