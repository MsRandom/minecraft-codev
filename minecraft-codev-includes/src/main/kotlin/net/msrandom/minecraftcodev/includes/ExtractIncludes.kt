package net.msrandom.minecraftcodev.includes

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
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
        @InputArtifact get

    abstract val classpath: FileCollection
        @Classpath
        @InputArtifactDependencies
        get

    override fun transform(outputs: TransformOutputs) {
        val input = inputFile.get().asFile.toPath()
        val output = outputs.file(input.fileName.toString()).toPath()

        val handler =
            runBlocking {
                val inputHashes =
                    classpath
                        .map {
                            async {
                                hashFile(it.toPath())
                            }
                        }.awaitAll()
                        .toHashSet()

                zipFileSystem(input).use {
                    val root = it.base.getPath("/")

                    val handler =
                        includedJarListingRules.firstNotNullOfOrNull { rule ->
                            rule.load(root)
                        }

                    if (handler == null) {
                        outputs.file(input)

                        return@runBlocking null
                    }

                    handler
                        .list(root)
                        .map { includedJar ->
                            async {
                                val path = it.base.getPath(includedJar.path)
                                val includeOutput = outputs.file(path.fileName.toString()).toPath()

                                val hash = hashFile(path)

                                if (hash !in inputHashes) {
                                    path.copyTo(includeOutput, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING)
                                }
                            }
                        }.awaitAll()

                    handler
                }
            }

        if (handler == null) {
            return
        }

        input.copyTo(output, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING)

        zipFileSystem(output).use { (fs) ->
            val root = fs.getPath("/")

            for (jar in handler.list(root)) {
                fs.getPath(jar.path).deleteExisting()
            }

            handler.remove(root)
        }
    }
}
