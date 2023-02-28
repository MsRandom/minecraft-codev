package net.msrandom.minecraftcodev.runs

import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.internal.AbstractValidatingNamedDomainObjectContainer
import org.gradle.api.internal.CollectionCallbackActionDecorator
import org.gradle.api.model.ObjectFactory
import org.gradle.api.plugins.ExtensionAware
import org.gradle.internal.reflect.Instantiator
import javax.inject.Inject

sealed interface RunsContainer : NamedDomainObjectContainer<MinecraftRunConfigurationBuilder>, ExtensionAware {
    /**
     * Directory used for storing download asset objects & indexes
     */
    val assetsDirectory: DirectoryProperty

    /**
     * Legacy resources, for beta 1.6 and below
     */
    val resourcesDirectory: DirectoryProperty
}

abstract class RunsContainerImpl @Inject constructor(instantiator: Instantiator, callbackActionDecorator: CollectionCallbackActionDecorator, private val objects: ObjectFactory) :
    AbstractValidatingNamedDomainObjectContainer<MinecraftRunConfigurationBuilder>(
        MinecraftRunConfigurationBuilder::class.java,
        instantiator,
        callbackActionDecorator
    ),
    RunsContainer {
    override fun doCreate(name: String): MinecraftRunConfigurationBuilder =
        objects.newInstance(MinecraftRunConfigurationBuilder::class.java, name, this)
}
