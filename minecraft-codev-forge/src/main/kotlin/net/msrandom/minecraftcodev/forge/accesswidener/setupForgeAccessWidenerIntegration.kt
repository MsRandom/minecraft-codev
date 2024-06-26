package net.msrandom.minecraftcodev.forge.accesswidener

import kotlinx.serialization.json.decodeFromStream
import net.msrandom.minecraftcodev.accesswidener.AccessWidenerExtension
import net.msrandom.minecraftcodev.accesswidener.MinecraftCodevAccessWidenerPlugin
import net.msrandom.minecraftcodev.core.MinecraftCodevExtension
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin.Companion.json
import net.msrandom.minecraftcodev.forge.UserdevConfig
import org.cadixdev.at.io.AccessTransformFormats
import org.gradle.api.Project
import java.nio.file.FileSystem
import java.nio.file.Path
import java.util.jar.Manifest
import kotlin.io.path.exists
import kotlin.io.path.inputStream

fun FileSystem.findAccessTransformers(): List<Path> {
    val transformers = mutableListOf<Path>()

    val meta = getPath("META-INF", "MANIFEST.MF")

    val manifest = meta.takeIf(Path::exists)?.inputStream()?.use(::Manifest)
    val accessTransformerName = manifest?.mainAttributes?.getValue("FMLAT") ?: "accesstransformer.cfg"
    val accessTransformerPath = getPath("META-INF", accessTransformerName)

    if (accessTransformerPath.exists()) {
        transformers.add(accessTransformerPath)
    }

    val configPath = getPath("config.json")
    if (configPath.exists()) {
        transformers.addAll(configPath.inputStream().use { json.decodeFromStream<UserdevConfig>(it) }.ats.map(::getPath))
    }

    return transformers
}

fun Project.setupForgeAccessWidenerIntegration() {
    plugins.withType(MinecraftCodevAccessWidenerPlugin::class.java) {
        val codev = extensions.getByType(MinecraftCodevExtension::class.java)
        val accessWidener = codev.extensions.getByType(AccessWidenerExtension::class.java)

        accessWidener.zipAccessWidenerResolution.add { _, fileSystem, _, data ->
            val accessTransformers = fileSystem.findAccessTransformers()

            if (accessTransformers.isEmpty()) {
                return@add false
            }

            for (accessTransformerPath in accessTransformers) {
                val accessTransformer = data.decorate(accessTransformerPath.inputStream()).reader().use(AccessTransformFormats.FML::read)
                val visitor = data.visitor

                visitor.visitHeader(data.namespace)

                for ((name, classValue) in accessTransformer.classes) {
                    visitor.visitClass(name, classValue.get().access)

                    for ((fieldName, fieldValue) in classValue.fields) {
                        visitor.visitField(name, fieldName, null, fieldValue.access, fieldValue.final)
                    }

                    for ((methodSignature, methodValue) in classValue.methods) {
                        visitor.visitMethod(
                            name,
                            methodSignature.name,
                            methodSignature.descriptor.toString(),
                            methodValue.access,
                            methodValue.final,
                        )
                    }
                }
            }

            true
        }
    }
}
