package net.msrandom.minecraftcodev.fabric.mappings

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.jsonPrimitive
import net.fabricmc.accesswidener.AccessWidenerReader
import net.fabricmc.accesswidener.AccessWidenerWriter
import net.fabricmc.accesswidener.ForwardingVisitor
import net.fabricmc.mappingio.tree.MappingTreeView
import net.msrandom.minecraftcodev.core.MappingsNamespace
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin.Companion.json
import net.msrandom.minecraftcodev.fabric.MinecraftCodevFabricPlugin.Companion.MOD_JSON
import net.msrandom.minecraftcodev.remapper.ExtraFileRemapper
import java.nio.file.FileSystem
import kotlin.io.path.inputStream
import kotlin.io.path.notExists
import kotlin.io.path.writeText

class AccessWidenerRemapper : ExtraFileRemapper {
    override fun invoke(mappings: MappingTreeView, fileSystem: FileSystem, sourceNamespace: String, targetNamespace: String) {
        val modJson = fileSystem.getPath(MOD_JSON)

        if (modJson.notExists()) {
            return
        }

        val json =
            modJson.inputStream().use {
                json.decodeFromStream<JsonObject>(it)
            }

        val accessWidener = json["accessWidener"]?.jsonPrimitive?.contentOrNull?.let(fileSystem::getPath) ?: return

        if (accessWidener.notExists()) {
            return
        }

        val writer = AccessWidenerWriter()

        val reader =
            AccessWidenerReader(
                object : ForwardingVisitor(writer) {
                    private var sourceNamespaceId = mappings.getNamespaceId(sourceNamespace)
                    private val targetNamespaceId = mappings.getNamespaceId(targetNamespace)

                    private fun String?.orNull() = if (this == null || this == "null") null else this

                    override fun visitHeader(namespace: String) {
                        sourceNamespaceId = mappings.getNamespaceId(namespace.takeUnless { it == "official" } ?: MappingsNamespace.OBF)

                        super.visitHeader(targetNamespace.takeUnless { it == MappingsNamespace.OBF } ?: "official")
                    }

                    override fun visitClass(
                        name: String,
                        access: AccessWidenerReader.AccessType,
                        transitive: Boolean,
                    ) {
                        val remapped = mappings.getClass(name, sourceNamespaceId)?.getName(targetNamespaceId).orNull()

                        super.visitClass(remapped ?: name, access, transitive)
                    }

                    override fun visitMethod(
                        owner: String,
                        name: String,
                        descriptor: String,
                        access: AccessWidenerReader.AccessType,
                        transitive: Boolean,
                    ) {
                        val classView = mappings.getClass(owner, sourceNamespaceId)
                        val method = classView?.getMethod(name, descriptor, sourceNamespaceId)

                        val remappedClass = classView?.getName(targetNamespaceId).orNull()
                        val remapped = method?.getName(targetNamespaceId).orNull()
                        val remappedDesc = method?.getDesc(targetNamespaceId).orNull()

                        super.visitMethod(remappedClass ?: owner, remapped ?: name, remappedDesc ?: descriptor, access, transitive)
                    }

                    override fun visitField(
                        owner: String,
                        name: String,
                        descriptor: String,
                        access: AccessWidenerReader.AccessType,
                        transitive: Boolean,
                    ) {
                        val classView = mappings.getClass(owner, sourceNamespaceId)
                        val field = classView?.getField(name, descriptor, sourceNamespaceId)
                        val remappedClass = classView?.getName(targetNamespaceId).orNull()
                        val remapped = field?.getName(targetNamespaceId).orNull()
                        val remappedDesc = field?.getDesc(targetNamespaceId).orNull()

                        super.visitField(remappedClass ?: owner, remapped ?: name, remappedDesc ?: descriptor, access, transitive)
                    }
                },
            )

        accessWidener.inputStream().bufferedReader().use {
            reader.read(it, sourceNamespace)
        }

        accessWidener.writeText(writer.writeString())
    }
}