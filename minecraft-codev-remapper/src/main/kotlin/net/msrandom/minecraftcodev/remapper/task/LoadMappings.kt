package net.msrandom.minecraftcodev.remapper.task

import net.fabricmc.mappingio.format.Tiny2Writer
import net.msrandom.minecraftcodev.core.task.CachedMinecraftTask
import net.msrandom.minecraftcodev.core.utils.cacheExpensiveOperation
import net.msrandom.minecraftcodev.core.utils.getAsPath
import net.msrandom.minecraftcodev.remapper.loadMappings
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.*
import org.gradle.process.ExecOperations
import javax.inject.Inject
import kotlin.io.path.bufferedWriter

const val LOAD_MAPPINGS_OPERATION_VERSION = 1

@CacheableTask
abstract class LoadMappings : CachedMinecraftTask() {
    abstract val mappings: ConfigurableFileCollection
        @InputFiles
        @PathSensitive(PathSensitivity.NONE)
        get

    abstract val output: RegularFileProperty
        @OutputFile
        get

    abstract val javaExecutable: RegularFileProperty
        @Internal get

    abstract val execOperations: ExecOperations
        @Inject get

    init {
        run {
            output.set(temporaryDir.resolve("mappings.tiny"))
        }
    }

    @TaskAction
    fun load() {
        cacheExpensiveOperation(cacheParameters.directory.getAsPath(), "mappings-$LOAD_MAPPINGS_OPERATION_VERSION", mappings, output.getAsPath()) { (output) ->
            val mappings = loadMappings(mappings, javaExecutable.get(), cacheParameters, execOperations)

            output.bufferedWriter().use { writer ->
                mappings.accept(Tiny2Writer(writer, false))
            }
        }
    }
}
