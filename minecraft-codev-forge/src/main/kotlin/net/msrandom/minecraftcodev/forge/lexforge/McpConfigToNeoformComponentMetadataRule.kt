package net.msrandom.minecraftcodev.forge.lexforge

import kotlinx.serialization.json.decodeFromStream
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin.Companion.json
import net.msrandom.minecraftcodev.core.utils.withCachedResource
import net.msrandom.minecraftcodev.forge.McpConfig
import net.msrandom.minecraftcodev.forge.disableVariant
import org.gradle.api.artifacts.ComponentMetadataContext
import org.gradle.api.artifacts.ComponentMetadataRule
import org.gradle.api.artifacts.repositories.RepositoryResourceAccessor
import java.io.File
import java.util.zip.ZipInputStream
import javax.inject.Inject

abstract class McpConfigToNeoformComponentMetadataRule @Inject constructor(private val cacheDirectory: File) : ComponentMetadataRule {
    abstract val repositoryResourceAccessor: RepositoryResourceAccessor
        @Inject get

    override fun execute(context: ComponentMetadataContext) {
        val id = context.details.id

        val fileName = "${id.name}-${id.version}.zip"
        val path = "${id.group.replace('.', '/')}/${id.name}/${id.version}/$fileName"

        var mcpConfig: McpConfig? = null

        repositoryResourceAccessor.withCachedResource(cacheDirectory, path) {
            val zipStream = ZipInputStream(it)

            while (true) {
                val entry = zipStream.nextEntry ?: break

                try {
                    if (entry.name == "config.json") {
                        mcpConfig = json.decodeFromStream<McpConfig>(zipStream)
                        break
                    }
                } finally {
                    zipStream.closeEntry()
                }
            }
        }

        if (mcpConfig == null) {
            return
        }

        context.details.allVariants { variant ->
            // Disable all implicit variants
            variant.withCapabilities {
                if (it.capabilities.none { it.group == "net.minecraftforge" }) {
                    // Not our mcpData variant
                    it.disableVariant()
                }
            }
        }

        context.details.addVariant("mcpData") { variant ->
            variant.withFiles {
                it.addFile(fileName)
            }

            variant.withDependencies { dependencies ->
                for (function in mcpConfig.functions.values) {
                    dependencies.add(function.version);
                }
            }

            variant.withCapabilities {
                // Capability here will not matter much, it just needs to be implicit
                //  this has to be picked using attributes
                it.addCapability(id.group, id.name, id.version);
                it.addCapability("net.minecraftforge", id.name, id.version);
            }
        }
    }
}
