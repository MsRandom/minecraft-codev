package net.msrandom.minecraftcodev.decompiler.resolve

import org.gradle.internal.component.external.model.DefaultModuleComponentArtifactIdentifier
import org.gradle.internal.component.external.model.ModuleComponentArtifactIdentifier
import org.gradle.internal.component.external.model.ModuleComponentArtifactMetadata
import org.gradle.internal.component.external.model.ModuleComponentFileArtifactIdentifier

class DecompiledComponentArtifactMetadata(
    val delegate: ModuleComponentArtifactMetadata,
    private val id: DecompiledComponentIdentifier,
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
