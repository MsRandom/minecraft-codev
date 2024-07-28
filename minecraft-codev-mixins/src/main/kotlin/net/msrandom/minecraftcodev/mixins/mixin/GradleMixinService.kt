package net.msrandom.minecraftcodev.mixins.mixin

import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.spongepowered.asm.launch.platform.container.IContainerHandle
import org.spongepowered.asm.mixin.MixinEnvironment
import org.spongepowered.asm.mixin.MixinEnvironment.Phase
import org.spongepowered.asm.mixin.MixinEnvironment.Side
import org.spongepowered.asm.mixin.Mixins
import org.spongepowered.asm.mixin.transformer.IMixinTransformer
import org.spongepowered.asm.mixin.transformer.IMixinTransformerFactory
import org.spongepowered.asm.service.IClassBytecodeProvider
import org.spongepowered.asm.service.IClassProvider
import org.spongepowered.asm.service.IClassTracker
import org.spongepowered.asm.service.MixinServiceAbstract
import org.spongepowered.asm.util.IConsumer
import java.io.File
import java.io.FileNotFoundException
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Path
import javax.annotation.concurrent.NotThreadSafe
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.inputStream

@NotThreadSafe
class GradleMixinService : MixinServiceAbstract() {
    lateinit var phaseConsumer: IConsumer<Phase>
        private set

    private lateinit var classpath: URLClassLoader

    val transformer: IMixinTransformer by lazy {
        getInternal(IMixinTransformerFactory::class.java).createTransformer()
    }

    /**
     * Thread safe accessor
     */
    fun <R> use(
        classpath: Iterable<File>,
        side: Side,
        action: GradleMixinService.() -> R,
    ) = synchronized(this) {
        this.classpath = URLClassLoader(classpath.map { it.toURI().toURL() }.toTypedArray(), javaClass.classLoader)

        (registeredConfigsField[null] as MutableCollection<*>).clear()

        sideField[MixinEnvironment.getCurrentEnvironment()] = Side.UNKNOWN
        MixinEnvironment.getCurrentEnvironment().side = side

        @Suppress("DEPRECATION")
        MixinEnvironment.getCurrentEnvironment().mixinConfigs.clear()

        Mixins.getConfigs().clear()

        this.action()
    }

    override fun getName() = "Gradle"

    override fun isValid() = true

    override fun getClassProvider() =
        object : IClassProvider {
            @Deprecated("Deprecated in Java", ReplaceWith("emptyArray<URL>()", "java.net.URL"))
            override fun getClassPath() = emptyArray<URL>()

            override fun findClass(name: String) = Class.forName(name)

            override fun findClass(
                name: String,
                initialize: Boolean,
            ) = Class.forName(name, initialize, javaClass.classLoader)

            override fun findAgentClass(
                name: String,
                initialize: Boolean,
            ) = findClass(name, initialize)
        }

    override fun getBytecodeProvider() =
        object : IClassBytecodeProvider {
            override fun getClassNode(name: String) =
                getResourceAsStream(name.replace('.', '/') + ".class")
                    ?.use(::ClassReader)
                    ?.let { reader -> ClassNode().also { reader.accept(it, 0) } }
                    ?: throw FileNotFoundException(name)

            override fun getClassNode(
                name: String,
                runTransformers: Boolean,
            ) = getClassNode(name)

            override fun getClassNode(name: String, runTransformers: Boolean, readerFlags: Int) = getClassNode(name)
        }

    override fun getTransformerProvider() = null

    override fun getClassTracker() =
        object : IClassTracker {
            override fun registerInvalidClass(className: String?) = Unit

            override fun isClassLoaded(className: String?) = false

            override fun getClassRestrictions(className: String?) = ""
        }

    override fun getAuditTrail() = null

    override fun getPlatformAgents() = listOf("org.spongepowered.asm.launch.platform.MixinPlatformAgentDefault")

    override fun getPrimaryContainer() =
        object : IContainerHandle {
            override fun getId() = "codev"

            override fun getDescription() = "Minecraft Codev Dummy Mixin Container"

            override fun getAttribute(name: String?) = null

            override fun getNestedContainers() = emptyList<IContainerHandle>()
        }

    override fun getResourceAsStream(name: String) = classpath.getResourceAsStream(name) ?: Path(name).takeIf(Path::exists)?.inputStream()

    @Deprecated("Deprecated in Java")
    override fun wire(
        phase: Phase,
        phaseConsumer: IConsumer<Phase>,
    ) {
        @Suppress("DEPRECATION")
        super.wire(phase, phaseConsumer)
        this.phaseConsumer = phaseConsumer
    }

    companion object {
        private val registeredConfigsField = Mixins::class.java.getDeclaredField("registeredConfigs").apply { isAccessible = true }
        private val sideField = MixinEnvironment::class.java.getDeclaredField("side").apply { isAccessible = true }
    }
}
