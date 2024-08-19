package net.msrandom.minecraftcodev.mixins.task

import net.msrandom.minecraftcodev.core.MinecraftCodevExtension
import net.msrandom.minecraftcodev.core.utils.cacheExpensiveOperation
import net.msrandom.minecraftcodev.core.utils.extension
import net.msrandom.minecraftcodev.core.utils.walk
import net.msrandom.minecraftcodev.core.utils.zipFileSystem
import net.msrandom.minecraftcodev.mixins.MixinsExtension
import net.msrandom.minecraftcodev.mixins.mixin.GradleMixinService
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.work.ChangeType
import org.gradle.work.InputChanges
import org.spongepowered.asm.mixin.MixinEnvironment.Side
import org.spongepowered.asm.mixin.Mixins
import org.spongepowered.asm.service.MixinService
import java.io.File
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.*

@CacheableTask
abstract class Mixin : DefaultTask() {
    abstract val inputFiles: ConfigurableFileCollection
        @InputFiles
        @Classpath
        @SkipWhenEmpty
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

    abstract val outputDirectory: DirectoryProperty
        @OutputDirectory get

    val outputFiles: FileCollection
        @Internal get() = project.fileTree(outputDirectory)

    init {
        outputDirectory.convention(project.layout.dir(project.provider { temporaryDir }))

        side.convention(Side.UNKNOWN)
    }

    private fun mixin(input: Path, output: Path) {
        (MixinService.getService() as GradleMixinService).use(classpath + mixinFiles + project.files(input), side.get()) {
            CLASSPATH@ for (mixinFile in mixinFiles + project.files(input)) {
                zipFileSystem(mixinFile.toPath()).use fs@{
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
                        return@fs
                    }

                    Mixins.addConfigurations(*handler.list(root).toTypedArray())
                }
            }

            zipFileSystem(input).use { (inputFs) ->
                val root = inputFs.getPath("/")

                zipFileSystem(output, true).use { (outputFs) ->
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

    @TaskAction
    private fun mixin(inputChanges: InputChanges) {
        for (inputChange in inputChanges.getFileChanges(inputFiles)) {
            val input = inputChange.file.toPath()
            val output = outputDirectory.asFile.get().toPath().resolve("${input.nameWithoutExtension}-with-mixins.${input.extension}")

            if (inputChange.changeType == ChangeType.MODIFIED) {
                output.deleteExisting()
            } else if (inputChange.changeType == ChangeType.REMOVED) {
                output.deleteExisting()

                continue
            }

            project.cacheExpensiveOperation("mixin", classpath + mixinFiles + project.files(input), output) {
                mixin(input, it)
            }
        }
    }
}
