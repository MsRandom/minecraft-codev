package net.msrandom.minecraftcodev.fabric

import net.fabricmc.mappingio.MappingVisitor
import net.fabricmc.mappingio.adapter.MappingNsCompleter
import net.fabricmc.mappingio.adapter.MappingNsRenamer
import net.fabricmc.mappingio.format.Tiny1Reader
import net.fabricmc.mappingio.format.Tiny2Reader
import net.msrandom.minecraftcodev.remapper.MappingNamespace
import net.msrandom.minecraftcodev.remapper.MinecraftCodevRemapperPlugin
import net.msrandom.minecraftcodev.remapper.RemapperExtension
import net.msrandom.minecraftcodev.core.MinecraftCodevExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import java.io.InputStream
import kotlin.io.path.exists
import kotlin.io.path.inputStream

class MinecraftCodevFabricPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.plugins.withType(MinecraftCodevRemapperPlugin::class.java) {
            val remapper = target
                .extensions.getByType(MinecraftCodevExtension::class.java)
                .extensions.getByType(RemapperExtension::class.java)

            remapper.mappingsResolution { path, extension, visitor, decorate, existingMappings ->
                if (extension == "tiny") {
                    readTiny(visitor, path.inputStream().decorate())
                    true
                } else {
                    false
                }
            }

            remapper.zipMappingsResolution { visitor, decorate, _, _ ->
                val tiny = getPath("mappings/mappings.tiny")
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

            val namespaceCompleter = if (MappingNamespace.INTERMEDIARY in parts) {
                MappingNsCompleter(visitor, mapOf(MappingNamespace.NAMED to MappingNamespace.INTERMEDIARY), true)
            } else {
                visitor
            }

            val renamer = MappingNsRenamer(namespaceCompleter, mapOf("official" to MappingNamespace.OBF))

            when (version) {
                1 -> Tiny1Reader.read(reader, renamer)
                2 -> Tiny2Reader.read(reader, renamer)
                else -> throw IllegalArgumentException("Unknown tiny mappings version found")
            }
        }
    }
}
