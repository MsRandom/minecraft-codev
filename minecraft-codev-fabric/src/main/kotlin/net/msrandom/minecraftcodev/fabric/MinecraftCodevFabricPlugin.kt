package net.msrandom.minecraftcodev.fabric

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromStream
import net.fabricmc.mappingio.MappingVisitor
import net.fabricmc.mappingio.adapter.MappingNsCompleter
import net.fabricmc.mappingio.adapter.MappingNsRenamer
import net.fabricmc.mappingio.format.Tiny1Reader
import net.fabricmc.mappingio.format.Tiny2Reader
import net.msrandom.minecraftcodev.core.MappingsNamespace
import net.msrandom.minecraftcodev.core.MinecraftCodevExtension
import net.msrandom.minecraftcodev.core.utils.applyPlugin
import net.msrandom.minecraftcodev.fabric.mixin.FabricMixinConfigHandler
import net.msrandom.minecraftcodev.fabric.runs.FabricRunsDefaultsContainer
import net.msrandom.minecraftcodev.mixins.MinecraftCodevMixinsPlugin
import net.msrandom.minecraftcodev.mixins.MixinsExtension
import net.msrandom.minecraftcodev.remapper.MinecraftCodevRemapperPlugin
import net.msrandom.minecraftcodev.remapper.RemapperExtension
import net.msrandom.minecraftcodev.runs.MinecraftCodevRunsPlugin
import net.msrandom.minecraftcodev.runs.RunConfigurationDefaultsContainer
import net.msrandom.minecraftcodev.runs.RunsContainer
import org.gradle.api.Plugin
import org.gradle.api.plugins.PluginAware
import java.io.InputStream
import kotlin.io.path.exists
import kotlin.io.path.inputStream

class MinecraftCodevFabricPlugin<T : PluginAware> : Plugin<T> {
    override fun apply(target: T) = applyPlugin(target) {
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

        plugins.withType(MinecraftCodevMixinsPlugin::class.java) {
            val mixins = extensions.getByType(MinecraftCodevExtension::class.java).extensions.getByType(MixinsExtension::class.java)

            mixins.rules.add { root ->
                val mod = root.resolve(MOD_JSON)

                if (mod.exists()) {
                    mod.inputStream().use {
                        val json = Json.decodeFromStream<JsonObject>(it)
                        val mixinsElement = json["mixins"]

                        if (mixinsElement != null) {
                            FabricMixinConfigHandler(mixinsElement, json, MOD_JSON)
                        } else {
                            null
                        }
                    }
                } else {
                    null
                }
            }
        }

        plugins.withType(MinecraftCodevRunsPlugin::class.java) {
            val defaults = extensions.getByType(MinecraftCodevExtension::class.java)
                .extensions.getByType(RunsContainer::class.java)
                .extensions.getByType(RunConfigurationDefaultsContainer::class.java)

            defaults.extensions.create("fabric", FabricRunsDefaultsContainer::class.java, defaults)
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

        private const val MOD_JSON = "fabric.mod.json"
    }
}
