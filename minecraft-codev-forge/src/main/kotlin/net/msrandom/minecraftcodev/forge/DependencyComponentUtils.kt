package net.msrandom.minecraftcodev.forge

import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.FileCollectionDependency
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.component.ComponentArtifactIdentifier
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier

// Finds if component ID is likely from DSL dependency
//  Should probably move away from using this but the DSL dependency is the only (non-internal) way I can find to access things like the group and version range
fun isComponentFromDependency(componentId: ComponentIdentifier, dependency: Dependency) =
    if (dependency is ProjectDependency) {
        componentId is ProjectComponentIdentifier && componentId.projectPath == dependency.path
    } else if (dependency is FileCollectionDependency) {
        // We don't check specifically for a subclass of ComponentIdentifier,
        //  but a ComponentArtifactIdentifier since file dependencies (as of writing this) have components that implement that.
        //  We also only check the file name, as a best-effort guess. Gradle does not expose the actual File in any way.
        componentId is ComponentArtifactIdentifier && !dependency.files.filter { it.name == componentId.displayName }.isEmpty
    } else if (dependency is ModuleDependency) {
        componentId is ModuleComponentIdentifier && dependency.group == componentId.group && dependency.name == componentId.module
    } else {
        false
    }
