package net.msrandom.minecraftcodev.mixins.task

import net.msrandom.minecraftcodev.core.utils.getAsPath
import net.msrandom.minecraftcodev.core.utils.walk
import net.msrandom.minecraftcodev.core.utils.zipFileSystem
import net.msrandom.minecraftcodev.mixins.mixin.GradleMixinService
import net.msrandom.minecraftcodev.mixins.mixinListingRules
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
import java.nio.file.StandardCopyOption
import kotlin.io.path.*

@CacheableTask
abstract class Mixin : DefaultTask() {
    abstract val inputFile: RegularFileProperty
        @InputFile
        @Classpath
        get

    abstract val mixinFiles: ConfigurableFileCollection
        @InputFiles
        @Classpath
        get

    abstract val classpath: ConfigurableFileCollection
        @InputFiles
        @CompileClasspath
        get

    abstract val side: Property<Side>
        @Input get

    abstract val outputFile: RegularFileProperty
        @OutputFile get

    init {
        outputFile.convention(
            project.layout.file(
                inputFile.map {
                    temporaryDir.resolve("${it.asFile.nameWithoutExtension}-with-mixins.${it.asFile.extension}")
                },
            ),
        )

        side.convention(Side.UNKNOWN)
    }

    @TaskAction
    fun mixin() {
        val input = inputFile.getAsPath()
        val output = outputFile.getAsPath()

        (MixinService.getService() as GradleMixinService).use(classpath + mixinFiles + project.files(input), side.get()) {
            CLASSPATH@ for (mixinFile in mixinFiles + project.files(input)) {
                zipFileSystem(mixinFile.toPath()).use fs@{
                    val root = it.getPath("/")

                    val handler =
                        mixinListingRules.firstNotNullOfOrNull { rule ->
                            rule.load(root)
                        }

                    if (handler == null) {
                        return@fs
                    }

                    Mixins.addConfigurations(*handler.list(root).toTypedArray())
                }
            }

            zipFileSystem(input).use { inputFs ->
                val root = inputFs.getPath("/")

                zipFileSystem(output, true).use { outputFs ->
                    root.walk {
                        for (path in filter(Path::isRegularFile)) {
                            val pathString = path.toString()
                            val outputPath = outputFs.getPath(pathString)

                            outputPath.parent?.createDirectories()

                            if (pathString.endsWith(".class")) {
                                val pathName = root.relativize(path).toString()

                                val name =
                                    pathName
                                        .substring(0, pathName.length - ".class".length)
                                        .replace(File.separatorChar, '.')

                                outputPath.writeBytes(transformer.transformClassBytes(name, name, path.readBytes()))
                            } else {
                                path.copyTo(outputPath, StandardCopyOption.COPY_ATTRIBUTES)
                            }
                        }
                    }
                }
            }
        }
    }
}
