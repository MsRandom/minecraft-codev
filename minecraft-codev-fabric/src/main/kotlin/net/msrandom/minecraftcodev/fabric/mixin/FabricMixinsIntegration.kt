package net.msrandom.minecraftcodev.fabric.mixin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromStream
import net.msrandom.minecraftcodev.core.MinecraftCodevExtension
import net.msrandom.minecraftcodev.core.utils.extension
import net.msrandom.minecraftcodev.fabric.MinecraftCodevFabricPlugin
import net.msrandom.minecraftcodev.mixins.MinecraftCodevMixinsPlugin
import net.msrandom.minecraftcodev.mixins.MixinsExtension
import org.gradle.api.Project
import kotlin.io.path.exists
import kotlin.io.path.inputStream

fun Project.setupFabricMixinsIntegration() {
    plugins.withType(MinecraftCodevMixinsPlugin::class.java) {
        val mixins = extension<MinecraftCodevExtension>().extension<MixinsExtension>()

        mixins.rules.add { root ->
            val mod = root.resolve(MinecraftCodevFabricPlugin.MOD_JSON)

            if (mod.exists()) {
                mod.inputStream().use {
                    val json = Json.decodeFromStream<JsonObject>(it)
                    val mixinsElement = json["mixins"]

                    if (mixinsElement != null) {
                        FabricMixinConfigHandler(mixinsElement, json, MinecraftCodevFabricPlugin.MOD_JSON)
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
