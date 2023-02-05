package net.msrandom.minecraftcodev.fabric

import net.fabricmc.mappingio.MappingVisitor
import net.fabricmc.mappingio.adapter.MappingNsCompleter
import net.fabricmc.mappingio.adapter.MappingNsRenamer
import net.fabricmc.mappingio.format.Tiny1Reader
import net.fabricmc.mappingio.format.Tiny2Reader
import net.msrandom.minecraftcodev.core.MappingsNamespace
import net.msrandom.minecraftcodev.core.MinecraftCodevExtension
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin
import net.msrandom.minecraftcodev.core.utils.applyPlugin
import net.msrandom.minecraftcodev.remapper.MinecraftCodevRemapperPlugin
import net.msrandom.minecraftcodev.remapper.RemapperExtension
import org.gradle.api.Plugin
import org.gradle.api.plugins.PluginAware
import java.io.InputStream
import kotlin.io.path.exists
import kotlin.io.path.inputStream

class MinecraftCodevFabricPlugin<T : PluginAware> : Plugin<T> {
    override fun apply(target: T) {
        target.plugins.apply(MinecraftCodevPlugin::class.java)

        applyPlugin(target) {
            plugins.withType(MinecraftCodevRemapperPlugin::class.java) {
                val remapper = extensions.getByType(MinecraftCodevExtension::class.java).extensions.getByType(RemapperExtension::class.java)

                remapper.mappingsResolution.add { path, extension, visitor, _, decorate, _, _ ->
                    if (extension == "tiny") {
                        readTiny(visitor, path.inputStream().decorate())
                        true
                    } else {
                        false
                    }
                }

                remapper.zipMappingsResolution.add { _, fileSystem, visitor, _, decorate, _, _, _ ->
                    val tiny = fileSystem.getPath("mappings/mappings.tiny")
                    if (tiny.exists()) {
                        // Assuming tiny
                        readTiny(visitor, tiny.inputStream().decorate())
                        true
                    } else {
                        false
                    }
                }
            }
        }
    }

    private fun readTiny(visitor: MappingVisitor, stream: InputStream) {
        stream.bufferedReader().use { reader ->
            reader.mark(16)

            val parts = reader.readLine().split('\t')

            reader.reset()

            val version = when {
                parts[0].startsWith('v') -> parts[0].substring(1).toInt()
                parts[0] == "tiny" -> parts[1].toInt()
                else -> throw IllegalArgumentException("Invalid tiny header found")
            }

            val namespaceCompleter = if (INTERMEDIARY_MAPPINGS_NAMESPACE in parts) {
                MappingNsCompleter(visitor, mapOf(MinecraftCodevRemapperPlugin.NAMED_MAPPINGS_NAMESPACE to INTERMEDIARY_MAPPINGS_NAMESPACE), true)
            } else {
                visitor
            }

            val renamer = MappingNsRenamer(namespaceCompleter, mapOf("official" to MappingsNamespace.OBF))

            when (version) {
                1 -> Tiny1Reader.read(reader, renamer)
                2 -> Tiny2Reader.read(reader, renamer)
                else -> throw IllegalArgumentException("Unknown tiny mappings version found")
            }
        }
    }

    companion object {
        const val INTERMEDIARY_MAPPINGS_NAMESPACE = "intermediary"
    }
}
