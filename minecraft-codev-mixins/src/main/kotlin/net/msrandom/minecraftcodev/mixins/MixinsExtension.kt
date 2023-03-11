package net.msrandom.minecraftcodev.mixins

import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import java.nio.file.Path
import javax.inject.Inject

interface MixinConfigHandler {
    /**
     * List all the mixin config paths
     * @param root New root that matches the format for the root this handler was loaded with
     */
    fun list(root: Path): List<Path>

    /**
     * Remove mixin configs & their listing from their owner
     * @param root New root that matches the format for the root this handler was loaded with
     */
    fun remove(root: Path)
}

fun interface MixinConfigRule {
    /**
     * Load a handler for the mod in directory
     * @param directory The directory which the mod is in, can potentially be in a zip file system
     * @return A [MixinConfigHandler] to list the mixin configs or remove them, or null if this rule doesn't apply
     */
    fun load(directory: Path): MixinConfigHandler?
}

open class MixinsExtension @Inject constructor(objectFactory: ObjectFactory){
    val rules: ListProperty<MixinConfigRule> = objectFactory.listProperty(MixinConfigRule::class.java)
}
