package net.msrandom.minecraftcodev.mixins

import net.msrandom.minecraftcodev.core.MinecraftCodevExtension
import net.msrandom.minecraftcodev.core.utils.extension
import net.msrandom.minecraftcodev.core.utils.walk
import net.msrandom.minecraftcodev.core.utils.zipFileSystem
import net.msrandom.minecraftcodev.mixins.mixin.GradleMixinService
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.spongepowered.asm.mixin.MixinEnvironment.Side
import org.spongepowered.asm.mixin.Mixins
import org.spongepowered.asm.service.MixinService
import java.io.File
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

abstract class JarMixin : DefaultTask() {
    abstract val input: RegularFileProperty
        @InputFile get

    abstract val classpath: ConfigurableFileCollection
        @InputFiles
        @CompileClasspath
        get

    abstract val side: Property<Side>
        @Input get

    abstract val output: RegularFileProperty
        @OutputFile get

    @TaskAction
    fun mixin() {
        (MixinService.getService() as GradleMixinService).use(classpath + project.files(input), side.get()) {
            for (mixinFile in classpath) {
                zipFileSystem(mixinFile.toPath()).use {
                    val root = it.base.getPath("/")

                    val handler =
                        project
                            .extension<MinecraftCodevExtension>()
                            .extension<MixinsExtension>()
                            .rules
                            .get()
                            .firstNotNullOfOrNull { rule ->
                                rule.load(root)
                            }

                    if (handler == null) {
                        throw UnsupportedOperationException(
                            "Couldn't find mixin configs for $input, unsupported format.\n" +
                                "You can register new mixin loading rules with minecraft.mixins.rules",
                        )
                    }

                    Mixins.addConfigurations(*handler.list(root).toTypedArray())
                }
            }

            zipFileSystem(output.asFile.get().toPath()).use {
                val root = it.base.getPath("/")

                root.walk {
                    for (path in filter(Path::isRegularFile).filter { path ->
                        path.toString().endsWith(".class")
                    }) {
                        val pathName = root.relativize(path).toString()
                        val name =
                            pathName.substring(
                                0,
                                pathName.length - ".class".length,
                            ).replace(File.separatorChar, '.')
                        path.writeBytes(transformer.transformClassBytes(name, name, path.readBytes()))
                    }
                }
            }
        }
    }
}
