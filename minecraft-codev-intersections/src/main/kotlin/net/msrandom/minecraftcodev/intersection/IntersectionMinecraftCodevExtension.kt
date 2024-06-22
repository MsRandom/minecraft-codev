package net.msrandom.minecraftcodev.intersection

import net.msrandom.minecraftcodev.intersection.dependency.IntersectionDependency
import net.msrandom.minecraftcodev.intersection.dependency.IntersectionDependencyImpl
import org.gradle.api.artifacts.ModuleDependency

open class IntersectionMinecraftCodevExtension {
    operator fun invoke(vararg dependencies: ModuleDependency): IntersectionDependency = IntersectionDependencyImpl(dependencies.toList())

    fun call(vararg dependencies: ModuleDependency) = invoke(*dependencies)
}
