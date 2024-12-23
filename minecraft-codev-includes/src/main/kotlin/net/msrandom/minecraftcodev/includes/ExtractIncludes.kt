package net.msrandom.minecraftcodev.includes

import kotlinx.coroutines.*
import net.msrandom.minecraftcodev.core.utils.*
import org.gradle.api.artifacts.transform.CacheableTransform
import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.artifacts.transform.InputArtifactDependencies
import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.artifacts.transform.TransformParameters
import org.gradle.api.file.FileCollection
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.*
import java.nio.file.StandardCopyOption
import kotlin.io.path.*

@CacheableTransform
abstract class ExtractIncludes : TransformAction<TransformParameters.None> {
    abstract val inputFile: Provider<FileSystemLocation>
        @InputArtifact
        @PathSensitive(PathSensitivity.NONE)
        get

    abstract val classpath: FileCollection
        @Classpath
        @InputArtifactDependencies
        get

    override fun transform(outputs: TransformOutputs) {
        val input = inputFile.get().toPath()

        val handler = runBlocking {
            zipFileSystem(input).use {
                val root = it.getPath("/")

                val handler =
                    includedJarListingRules.firstNotNullOfOrNull { rule ->
                        rule.load(root)
                    }

                if (handler == null) {
                    return@use null
                }

                val inputHashes =
                    classpath
                        .map {
                            async {
                                hashFileSuspend(it.toPath())
                            }
                        }.awaitAll()
                        .toHashSet()

                handler
                    .list(root)
                    .map { includedJar ->
                        async {
                            withContext(Dispatchers.IO) {
                                val path = it.getPath(includedJar)
                                val hash = hashFileSuspend(path)

                                if (hash !in inputHashes) {
                                    println("Extracting $path from $input")

                                    val includeOutput = outputs.file(path.fileName.toString()).toPath()

                                    path.copyTo(includeOutput, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING)
                                } else {
                                    println("Skipping extracting $path from $input because hash $hash is in dependencies")
                                }
                            }
                        }
                    }.awaitAll()

                handler
            }
        }

        if (handler == null) {
            outputs.file(inputFile)

            return
        }

        val output = outputs.file(input.fileName.toString()).toPath()

        input.copyTo(output, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING)

        zipFileSystem(output).use { fs ->
            val root = fs.getPath("/")

            for (jar in handler.list(root)) {
                fs.getPath(jar).deleteExisting()
            }

            handler.remove(root)
        }
    }
}
