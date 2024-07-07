package net.msrandom.minecraftcodev.core.resolve.legacy

import net.msrandom.minecraftcodev.annotations.UnsafeForClient
import net.msrandom.minecraftcodev.annotations.UnsafeForCommon
import net.msrandom.minecraftcodev.core.resolve.MinecraftVersionMetadata
import net.msrandom.minecraftcodev.core.resolve.downloadMinecraftClient
import net.msrandom.minecraftcodev.core.utils.*
import org.gradle.api.Project
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Handle
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*
import java.io.IOException
import java.nio.file.FileSystem
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.io.path.*
import kotlin.reflect.KMutableProperty1

object LegacyJarSplitter {
    private val UNSAFE_FOR_COMMON = UnsafeForCommon::class.qualifiedName!!
    private val UNSAFE_FOR_CLIENT = UnsafeForClient::class.qualifiedName!!

    fun FileSystem.withAssets(action: (Path) -> Unit) {
        getPath("/").walk {
            filter {
                it.isRegularFile() &&
                    it.toString().let { path ->
                        !path.endsWith(".class") && (!path.startsWith("/META-INF") || (!path.endsWith(".DSA") && !path.endsWith(".SF")))
                    }
            }.forEach(action)
        }
    }

    // Copied from fabric's stitch and modified
    private fun <T> mergeLists(
        client: List<T>,
        server: List<T>,
        getName: T.() -> String,
    ): ListMergeResult<T> {
        val merged = mutableListOf<T>()
        val clientOnly = mutableListOf<T>()
        val serverOnly = mutableListOf<T>()
        var clientIndex = 0
        var serverIndex = 0

        while (clientIndex < client.size || serverIndex < server.size) {
            while (clientIndex < client.size && serverIndex < server.size && client[clientIndex].getName() == server[serverIndex].getName()) {
                merged.add(client[clientIndex])
                clientIndex++
                serverIndex++
            }

            while (clientIndex < client.size && server.none { it.getName() == client[clientIndex].getName() }) {
                merged.add(client[clientIndex])
                clientOnly.add(client[clientIndex])
                clientIndex++
            }

            while (serverIndex < server.size && client.none { it.getName() == server[serverIndex].getName() }) {
                merged.add(server[serverIndex])
                serverOnly.add(server[serverIndex])
                serverIndex++
            }
        }

        return ListMergeResult(merged, clientOnly, serverOnly)
    }

    private fun <T> addAnnotation(
        list: List<T>,
        property: KMutableProperty1<T, MutableList<AnnotationNode>?>,
        annotation: String,
    ) {
        for (value in list) {
            addAnnotation(value, property, annotation)
        }
    }

    private fun <T> addAnnotation(
        owner: T,
        property: KMutableProperty1<T, MutableList<AnnotationNode>?>,
        annotation: String,
    ) {
        var value = property(owner)

        if (value == null) {
            value = mutableListOf()
            property.set(owner, value)
        }

        value.add(AnnotationNode(Type.getObjectType(annotation).descriptor))
    }

    fun <R> useFileSystems(action: ((LockingFileSystem) -> Unit) -> R): R {
        val fileSystems = mutableListOf<LockingFileSystem>()
        try {
            return action(fileSystems::add)
        } finally {
            val throwables = mutableListOf<Throwable>()

            for (fileSystem in fileSystems) {
                try {
                    fileSystem.close()
                } catch (throwable: Throwable) {
                    throwables.add(throwable)
                }
            }

            if (throwables.isNotEmpty()) {
                val io = throwables.firstOrNull { it is IOException }

                if (io == null) {
                    throw RuntimeException().apply {
                        for (throwable in throwables) {
                            addSuppressed(throwable)
                        }
                    }
                } else {
                    throw io.apply {
                        for (throwable in throwables) {
                            if (throwable != io) {
                                addSuppressed(throwable)
                            }
                        }
                    }
                }
            }
        }
    }

    fun MethodNode.collectTypeReferences() =
        buildList {
            val descriptor = Type.getMethodType(desc)
            add(descriptor.returnType)
            addAll(descriptor.argumentTypes)

            for (instruction in instructions) {
                when (instruction) {
                    is FieldInsnNode -> {
                        add(Type.getObjectType(instruction.owner))
                        add(Type.getType(instruction.desc))
                    }

                    is FrameNode -> {
                        if (instruction.local != null) {
                            for (any in instruction.local) {
                                if (any is String) {
                                    add(Type.getObjectType(any))
                                }
                            }
                        }

                        if (instruction.stack != null) {
                            for (any in instruction.stack) {
                                if (any is String) {
                                    add(Type.getObjectType(any))
                                }
                            }
                        }
                    }

                    is InvokeDynamicInsnNode -> {
                        fun addHandle(handle: Handle) {
                            val bsmDescriptor = Type.getType(handle.desc)

                            if (bsmDescriptor.sort == Type.METHOD) {
                                add(bsmDescriptor.returnType)
                                addAll(bsmDescriptor.argumentTypes)
                            } else {
                                add(bsmDescriptor)
                            }
                            add(Type.getObjectType(handle.owner))
                        }

                        val invokeDescriptor = Type.getMethodType(instruction.desc)
                        add(invokeDescriptor.returnType)
                        addAll(invokeDescriptor.argumentTypes)

                        addHandle(instruction.bsm)

                        for (arg in instruction.bsmArgs) {
                            if (arg is Type) {
                                add(arg)
                            } else if (arg is Handle) {
                                addHandle(arg)
                            }
                        }
                    }

                    is LdcInsnNode -> {
                        val cst = instruction.cst
                        if (cst is Type) {
                            add(if (cst.sort == Type.ARRAY) cst.elementType else cst)
                        }
                    }

                    is MethodInsnNode -> {
                        val methodDescriptor = Type.getMethodType(instruction.desc)
                        add(methodDescriptor.returnType)
                        addAll(methodDescriptor.argumentTypes)
                        add(Type.getObjectType(instruction.owner))
                    }

                    is MultiANewArrayInsnNode -> add(Type.getType(instruction.desc).elementType)
                    is TypeInsnNode -> add(Type.getObjectType(instruction.desc))
                }
            }
        }

    fun split(
        project: Project,
        metadata: MinecraftVersionMetadata,
        server: Path,
    ): JarSplittingResult {
        val client = downloadMinecraftClient(project, metadata)
        val outputCommon = commonJarPath(project, metadata.id)
        val outputClient = clientJarPath(project, metadata.id)

        useFileSystems { handle ->
            val clientFs = zipFileSystem(client).also(handle)
            val serverFs = zipFileSystem(server).also(handle)
            val commonFs = zipFileSystem(outputCommon).also(handle)
            val newClientFs = zipFileSystem(outputClient).also(handle)

            val extraCommonTypes = hashSetOf<Type>()

            clientFs.base.getPath("/").walk {
                for (clientEntry in filter(Path::isRegularFile)) {
                    val pathName = clientEntry.toString()
                    if (pathName.endsWith(".class")) {
                        val serverEntry = serverFs.base.getPath(pathName)

                        if (serverEntry.exists()) {
                            // Shared entry
                            val clientReader = clientEntry.inputStream().use(::ClassReader)
                            val serverReader = serverEntry.inputStream().use(::ClassReader)
                            val clientNode = ClassNode()
                            val serverNode = ClassNode()

                            clientReader.accept(clientNode, 0)
                            serverReader.accept(serverNode, 0)

                            val mergedInterfaces = mergeLists(clientNode.interfaces, serverNode.interfaces, String::toString)
                            val mergedFields = mergeLists(clientNode.fields, serverNode.fields, FieldNode::name)
                            val mergedMethods = mergeLists(clientNode.methods, serverNode.methods) { name + desc }

                            extraCommonTypes.addAll(mergedInterfaces.client.map(Type::getObjectType))
                            extraCommonTypes.addAll(mergedFields.client.map { Type.getType(it.desc) })
                            extraCommonTypes.addAll(mergedMethods.client.flatMap { it.collectTypeReferences() })

                            addAnnotation(mergedFields.client, FieldNode::invisibleAnnotations, UNSAFE_FOR_COMMON)
                            addAnnotation(mergedFields.server, FieldNode::invisibleAnnotations, UNSAFE_FOR_CLIENT)
                            addAnnotation(mergedMethods.client, MethodNode::invisibleAnnotations, UNSAFE_FOR_COMMON)
                            addAnnotation(mergedMethods.server, MethodNode::invisibleAnnotations, UNSAFE_FOR_CLIENT)

                            serverNode.interfaces = mergedInterfaces.merged
                            serverNode.fields = mergedFields.merged
                            serverNode.methods = mergedMethods.merged

                            val writer = ClassWriter(serverReader, 0)
                            serverNode.accept(writer)

                            val path = commonFs.base.getPath(pathName)
                            serverEntry.copyTo(path, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING)
                            path.writeBytes(writer.toByteArray(), StandardOpenOption.WRITE, StandardOpenOption.CREATE)

                            newClientFs.base.getPath(pathName).deleteExisting()
                        } else {
                            clientEntry.copyTo(
                                newClientFs.base.getPath(pathName),
                                StandardCopyOption.COPY_ATTRIBUTES,
                                StandardCopyOption.REPLACE_EXISTING,
                            )
                        }
                    } else {
                        newClientFs.base.getPath(pathName).deleteExisting()
                    }
                }
            }

            for (extraCommonType in extraCommonTypes) {
                if (extraCommonType.sort == Type.OBJECT) {
                    val name = "${extraCommonType.internalName}.class"

                    val clientPath = clientFs.base.getPath(name)
                    val commonPath = commonFs.base.getPath(name)
                    if (clientPath.exists() && commonPath.notExists()) {
                        val reader = clientPath.inputStream().use(::ClassReader)
                        val node = ClassNode()
                        reader.accept(node, 0)

                        addAnnotation(node, ClassNode::invisibleAnnotations, UNSAFE_FOR_COMMON)

                        val writer = ClassWriter(reader, 0)
                        node.accept(writer)

                        commonPath.writeBytes(writer.toByteArray(), StandardOpenOption.WRITE, StandardOpenOption.CREATE)
                    }
                }
            }

            serverFs.base.getPath("/").walk {
                for (serverEntry in filter(Path::isRegularFile)) {
                    val name = serverEntry.toString()
                    val output = commonFs.base.getPath(name)
                    if (name.endsWith(".class")) {
                        if (output.notExists()) {
                            val reader = serverEntry.inputStream().use(::ClassReader)
                            val node = ClassNode()
                            reader.accept(node, 0)

                            addAnnotation(node, ClassNode::invisibleAnnotations, UNSAFE_FOR_CLIENT)

                            val writer = ClassWriter(reader, 0)
                            node.accept(writer)

                            output.writeBytes(writer.toByteArray(), StandardOpenOption.WRITE, StandardOpenOption.CREATE)
                        }
                    } else {
                        output.deleteExisting()
                    }
                }
            }

            copyAssets(clientFs.base, serverFs.base, commonFs.base, newClientFs.base)
        }

        return JarSplittingResult(
            outputCommon,
            outputClient,
        )
    }

    fun copyAssets(
        client: FileSystem,
        server: FileSystem,
        commonOut: FileSystem,
        clientOut: FileSystem,
        legacy: Boolean =
            server.getPath(
                "data",
            ).notExists(),
    ) {
        client.withAssets { path ->
            val name = path.toString()
            if (server.getPath(
                    name,
                ).notExists() || (!legacy && ("lang" in name || (path.parent.name == "assets" && path.name.startsWith('.'))))
            ) {
                val newPath = clientOut.getPath(name)
                newPath.parent?.createDirectories()
                path.copyTo(newPath, StandardCopyOption.COPY_ATTRIBUTES)
            }
        }

        server.withAssets { path ->
            val newPath = commonOut.getPath(path.toString())
            newPath.parent?.createDirectories()
            path.copyTo(newPath, StandardCopyOption.COPY_ATTRIBUTES)
        }
    }

    data class JarSplittingResult(val common: Path, val client: Path)

    data class ListMergeResult<T>(val merged: List<T>, val client: List<T>, val server: List<T>)
}
