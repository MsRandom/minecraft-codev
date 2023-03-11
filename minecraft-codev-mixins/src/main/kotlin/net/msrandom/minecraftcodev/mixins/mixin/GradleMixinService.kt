package net.msrandom.minecraftcodev.mixins.mixin

import org.spongepowered.asm.launch.platform.container.IContainerHandle
import org.spongepowered.asm.mixin.MixinEnvironment.Phase
import org.spongepowered.asm.service.*
import org.spongepowered.asm.util.IConsumer
import java.io.InputStream
import java.net.URL

class GradleMixinService : MixinServiceAbstract() {
    private lateinit var phaseConsumer: IConsumer<Phase>

    fun getPhaseConsumer() = phaseConsumer

    override fun getName() = "Gradle"
    override fun isValid() = true

    override fun getClassProvider() = object : IClassProvider {
        @Deprecated("Deprecated in Java", ReplaceWith("emptyArray<URL>()", "java.net.URL"))
        override fun getClassPath() = emptyArray<URL>()

        override fun findClass(name: String?): Class<*> {
            TODO("Not yet implemented")
        }

        override fun findClass(name: String?, initialize: Boolean): Class<*> {
            TODO("Not yet implemented")
        }

        override fun findAgentClass(name: String?, initialize: Boolean): Class<*> {
            TODO("Not yet implemented")
        }

    }

    override fun getBytecodeProvider() = object : IClassBytecodeProvider {
        override fun getClassNode(name: String?): org.objectweb.asm.tree.ClassNode {
            TODO("Not yet implemented")
        }

        override fun getClassNode(name: String?, runTransformers: Boolean): org.objectweb.asm.tree.ClassNode {
            TODO("Not yet implemented")
        }
    }

    override fun getTransformerProvider() = null

    override fun getClassTracker() = object : IClassTracker {
        override fun registerInvalidClass(className: String?) = Unit
        override fun isClassLoaded(className: String?) = false
        override fun getClassRestrictions(className: String?) = ""
    }

    override fun getAuditTrail() = null

    override fun getPlatformAgents() = listOf("org.spongepowered.asm.launch.platform.MixinPlatformAgentDefault")

    override fun getPrimaryContainer() = object : IContainerHandle {
        override fun getAttribute(name: String?) = null
        override fun getNestedContainers() = emptyList<IContainerHandle>()
    }

    override fun getResourceAsStream(name: String?): InputStream {
        TODO("Not yet implemented")
    }

    override fun wire(phase: Phase, phaseConsumer: IConsumer<Phase>) {
        super.wire(phase, phaseConsumer)
        this.phaseConsumer = phaseConsumer
    }
}
