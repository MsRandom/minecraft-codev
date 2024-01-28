package net.msrandom.minecraftcodev.fabric.accesswidener

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.jsonPrimitive
import net.fabricmc.accesswidener.AccessWidenerReader
import net.msrandom.minecraftcodev.accesswidener.AccessWidenerExtension
import net.msrandom.minecraftcodev.accesswidener.MinecraftCodevAccessWidenerPlugin
import net.msrandom.minecraftcodev.core.MinecraftCodevExtension
import net.msrandom.minecraftcodev.fabric.MinecraftCodevFabricPlugin
import org.gradle.api.Project
import kotlin.io.path.exists
import kotlin.io.path.inputStream

fun Project.setupFabricAccessWidenerIntegration() {
    plugins.withType(MinecraftCodevAccessWidenerPlugin::class.java) {
        val accessWidener = extensions.getByType(MinecraftCodevExtension::class.java).extensions.getByType(AccessWidenerExtension::class.java)

        accessWidener.zipAccessWidenerResolution.add { _, fileSystem, _, data ->
            val mod = fileSystem.getPath(MinecraftCodevFabricPlugin.MOD_JSON)

            if (!mod.exists()) {
                return@add false
            }

            mod.inputStream().use {
                val json = Json.decodeFromStream<JsonObject>(it)
                val accessWidenerPath = json["accessWidener"]?.jsonPrimitive?.content ?: return@add false

                data.decorate(fileSystem.getPath(accessWidenerPath).inputStream()).use {
                    AccessWidenerReader(data.visitor.onlyTransitives()).read(it.bufferedReader(), data.namespace)
                }
                true
            }
        }
    }
}
