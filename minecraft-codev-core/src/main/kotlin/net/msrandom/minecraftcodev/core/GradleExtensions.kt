package net.msrandom.minecraftcodev.core

import org.gradle.api.Named
import org.gradle.api.Project
import org.gradle.api.model.ObjectFactory

val Project.minecraftCodev: MinecraftCodevExtension
    get() = extensions.getByType(MinecraftCodevExtension::class.java)

inline fun <reified T : Named> Project.named(name: String): T = objects.named(name)
inline fun <reified T : Named> ObjectFactory.named(name: String): T = named(T::class.java, name)
