package net.msrandom.minecraftcodev.fabric.jarinjar

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.jsonArray
import net.msrandom.minecraftcodev.core.MinecraftCodevExtension
import net.msrandom.minecraftcodev.core.utils.extension
import net.msrandom.minecraftcodev.fabric.MinecraftCodevFabricPlugin
import net.msrandom.minecraftcodev.includes.IncludesExtension
import net.msrandom.minecraftcodev.includes.MinecraftCodevIncludesPlugin
import org.gradle.api.Project
import kotlin.io.path.exists
import kotlin.io.path.inputStream

fun Project.setupFabricIncludesIntegration() {
    plugins.withType(MinecraftCodevIncludesPlugin::class.java) {
        val includes = extension<MinecraftCodevExtension>().extension<IncludesExtension>()

        includes.rules.add { root ->
            val mod = root.resolve(MinecraftCodevFabricPlugin.MOD_JSON)

            if (mod.exists()) {
                mod.inputStream().use {
                    val json = Json.decodeFromStream<JsonObject>(it)
                    val mixinsElement = json["jars"]?.jsonArray

                    if (mixinsElement != null) {
                        FabricJarInJarHandler(mixinsElement, json, MinecraftCodevFabricPlugin.MOD_JSON)
                    } else {
                        null
                    }
                }
            } else {
                null
            }
        }
    }
}
