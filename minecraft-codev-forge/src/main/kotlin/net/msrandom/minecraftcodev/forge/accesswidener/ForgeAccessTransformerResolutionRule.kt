package net.msrandom.minecraftcodev.forge.accesswidener

import kotlinx.serialization.json.decodeFromStream
import net.msrandom.minecraftcodev.accesswidener.AccessModifierResolutionData
import net.msrandom.minecraftcodev.accesswidener.ZipAccessModifierResolutionRule
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin.Companion.json
import net.msrandom.minecraftcodev.forge.UserdevConfig
import org.cadixdev.at.io.AccessTransformFormats
import java.nio.file.FileSystem
import java.nio.file.Path
import java.util.jar.JarFile
import java.util.jar.Manifest
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

fun FileSystem.findAccessTransformers(): List<Path> {
    val transformers = mutableListOf<Path>()

    val meta = getPath(JarFile.MANIFEST_NAME)

    val manifest = meta.takeIf(Path::exists)?.inputStream()?.use(::Manifest)
    val accessTransformerName = manifest?.mainAttributes?.getValue("FMLAT") ?: "accesstransformer.cfg"
    val accessTransformerPath = getPath("META-INF", accessTransformerName)

    if (accessTransformerPath.exists()) {
        transformers.add(accessTransformerPath)
    }

    val configPath = getPath("config.json")
    if (configPath.exists()) {
        val userdev = configPath.inputStream().use { json.decodeFromStream<UserdevConfig>(it) }

        val accessTransformers =
            userdev.ats.flatMap {
                val path = getPath(it)

                if (path.isDirectory()) {
                    path.listDirectoryEntries()
                } else {
                    listOf(path)
                }
            }

        transformers.addAll(accessTransformers)
    }

    return transformers
}

class ForgeAccessTransformerResolutionRule : ZipAccessModifierResolutionRule {
    override fun load(path: Path, fileSystem: FileSystem, isJar: Boolean, data: AccessModifierResolutionData): Boolean {
        val accessTransformers = fileSystem.findAccessTransformers()

        if (accessTransformers.isEmpty()) {
            return false
        }

        for (accessTransformerPath in accessTransformers) {
            val accessTransformer = accessTransformerPath.inputStream().reader().use(AccessTransformFormats.FML::read)
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

        return true
    }
}
