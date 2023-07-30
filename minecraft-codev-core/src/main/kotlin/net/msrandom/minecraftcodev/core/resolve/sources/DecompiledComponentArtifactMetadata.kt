package net.msrandom.minecraftcodev.core.resolve.sources

import org.gradle.internal.component.external.model.DefaultModuleComponentArtifactIdentifier
import org.gradle.internal.component.external.model.ModuleComponentArtifactIdentifier
import org.gradle.internal.component.external.model.ModuleComponentArtifactMetadata
import org.gradle.internal.component.external.model.ModuleComponentFileArtifactIdentifier
import org.gradle.internal.component.model.ComponentArtifactMetadata
import org.gradle.internal.component.model.ModuleSources

class DecompiledComponentArtifactMetadata(
    val delegate: ModuleComponentArtifactMetadata,
    private val id: DecompiledComponentIdentifier,
    val selectedArtifacts: List<Pair<ModuleSources, List<ComponentArtifactMetadata>>>
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

class PassthroughDecompiledArtifactMetadata(val original: ComponentArtifactMetadata): ComponentArtifactMetadata by original
