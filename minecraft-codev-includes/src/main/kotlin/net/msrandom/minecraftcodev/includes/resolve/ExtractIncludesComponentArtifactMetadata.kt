package net.msrandom.minecraftcodev.includes.resolve

import org.gradle.internal.component.external.model.DefaultModuleComponentArtifactIdentifier
import org.gradle.internal.component.external.model.ModuleComponentArtifactIdentifier
import org.gradle.internal.component.external.model.ModuleComponentArtifactMetadata
import org.gradle.internal.component.external.model.ModuleComponentFileArtifactIdentifier

class ExtractIncludesComponentArtifactMetadata(
    val delegate: ModuleComponentArtifactMetadata,
    private val id: ExtractIncludesComponentIdentifier,
) : ModuleComponentArtifactMetadata by delegate {
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
