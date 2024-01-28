package net.msrandom.minecraftcodev.fabric

import net.msrandom.minecraftcodev.core.MappingsNamespace
import net.msrandom.minecraftcodev.core.MinecraftCodevExtension
import net.msrandom.minecraftcodev.core.ModInfo
import net.msrandom.minecraftcodev.core.ModInfoType
import net.msrandom.minecraftcodev.core.utils.applyPlugin
import net.msrandom.minecraftcodev.fabric.accesswidener.setupFabricAccessWidenerIntegration
import net.msrandom.minecraftcodev.fabric.jarinjar.setupFabricIncludesIntegration
import net.msrandom.minecraftcodev.fabric.mappings.setupFabricRemapperIntegration
import net.msrandom.minecraftcodev.fabric.mixin.setupFabricMixinsIntegration
import net.msrandom.minecraftcodev.fabric.runs.setupFabricRunsIntegration
import org.gradle.api.Plugin
import org.gradle.api.plugins.PluginAware
import java.util.jar.Manifest
import kotlin.io.path.exists
import kotlin.io.path.inputStream

class MinecraftCodevFabricPlugin<T : PluginAware> : Plugin<T> {
    override fun apply(target: T) = applyPlugin(target) {
        val codev = extensions.getByType(MinecraftCodevExtension::class.java)

        setupFabricAccessWidenerIntegration()
        setupFabricMixinsIntegration()
        setupFabricRemapperIntegration()
        setupFabricIncludesIntegration()
        setupFabricRunsIntegration()

        codev.modInfoDetectionRules.add {
            if (it.getPath(MOD_JSON).exists()) {
                ModInfo(ModInfoType.Platform, "fabric", 3)
            } else {
                null
            }
        }

        codev.modInfoDetectionRules.add {
            val path = it.getPath("META-INF", "MANIFEST.MF")

            if (path.exists() && path.inputStream().use(::Manifest).mainAttributes.keys.any { it.toString().startsWith("Fabric") }) {
                ModInfo(ModInfoType.Platform, "fabric", 2)
            } else {
                null
            }
        }

        codev.modInfoDetectionRules.add {
            val path = it.getPath("META-INF", "MANIFEST.MF")

            if (path.exists()) {
                path.inputStream().use(::Manifest).mainAttributes.getValue("Fabric-Mapping-Namespace")?.let {
                    val namespace = if (it == "official") {
                        MappingsNamespace.OBF
                    } else {
                        it
                    }

                    ModInfo(ModInfoType.Namespace, namespace, 3)
                }
            } else {
                null
            }
        }

        codev.modInfoDetectionRules.add {
            val path = it.getPath("META-INF", "MANIFEST.MF")

            if (path.exists()) {
                val attributes = path.inputStream().use(::Manifest).mainAttributes

                val versionAttribute = attributes.getValue("Fabric-Minecraft-Version") ?: attributes.getValue("Built-On-Minecraft")

                versionAttribute?.let {
                    ModInfo(ModInfoType.Version, it, 3)
                }
            } else {
                null
            }
        }
    }

    companion object {
        const val INTERMEDIARY_MAPPINGS_NAMESPACE = "intermediary"

        const val MOD_JSON = "fabric.mod.json"
    }
}
