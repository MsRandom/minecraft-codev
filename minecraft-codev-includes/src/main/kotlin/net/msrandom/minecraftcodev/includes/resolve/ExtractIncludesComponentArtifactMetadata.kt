package net.msrandom.minecraftcodev.includes.resolve

import org.gradle.internal.component.external.model.DefaultModuleComponentArtifactIdentifier
import org.gradle.internal.component.external.model.ModuleComponentArtifactIdentifier
import org.gradle.internal.component.external.model.ModuleComponentArtifactMetadata
import org.gradle.internal.component.external.model.ModuleComponentFileArtifactIdentifier
import org.gradle.internal.component.model.ComponentArtifactMetadata
import org.gradle.internal.component.model.DefaultIvyArtifactName
import org.gradle.internal.component.model.IvyArtifactName

class PassthroughExtractIncludesArtifactMetadata(val original: ComponentArtifactMetadata): ComponentArtifactMetadata by original

class ExtractIncludesComponentArtifactMetadata(
    val delegate: ModuleComponentArtifactMetadata,
    private val id: ExtractIncludesComponentIdentifier
) : ModuleComponentArtifactMetadata by delegate {
    override fun getId(): ModuleComponentArtifactIdentifier = delegate.id.let {
        if (it is DefaultModuleComponentArtifactIdentifier) {
            DefaultModuleComponentArtifactIdentifier(id, it.name)
        } else {
            ModuleComponentFileArtifactIdentifier(id, delegate.id.fileName)
        }
    }

    override fun getComponentId() = id
}

class ExtractedIncludeComponentArtifactMetadata(
    val owner: ModuleComponentArtifactMetadata,
    val path: String,
    private val id: ExtractIncludesComponentIdentifier,
    private val extension: String,
    private val classifier: String
) : ModuleComponentArtifactMetadata by owner {
    override fun getId(): ModuleComponentArtifactIdentifier = owner.id.let {
        if (it is DefaultModuleComponentArtifactIdentifier) {
            DefaultModuleComponentArtifactIdentifier(id, name)
        } else {
            ModuleComponentFileArtifactIdentifier(id, name.toString())
        }
    }

    override fun getComponentId() = id
    override fun getName() = DefaultIvyArtifactName(id.module, extension, extension, classifier)
}
