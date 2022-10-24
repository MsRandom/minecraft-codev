package net.msrandom.minecraftcodev.forge

import net.minecraftforge.srgutils.IMappingFile
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin.Companion.walk
import net.msrandom.minecraftcodev.core.LegacyJarSplitter.collectTypeReferences
import net.msrandom.minecraftcodev.core.LegacyJarSplitter.copyAssets
import net.msrandom.minecraftcodev.core.LegacyJarSplitter.withAssets
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import java.nio.file.FileSystem
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.*

object ForgeJarSplitter {
    private val annotationNames = hashSetOf(
        "Lnet/minecraftforge/api/distmarker/OnlyIn;",
        "Lcpw/mods/fml/relauncher/SideOnly;",
        "Lnet/minecraftforge/fml/relauncher/SideOnly;",
    )

    private fun workingName(name: String, mappings: IMappingFile): String? {
        val typeName = name.trim('/').substringBeforeLast(".")
        val mapping = mappings.getClass(typeName)
        return if (mapping == null) {
            val innerClassStart = name.lastIndexOf('$')
            if (innerClassStart == -1) {
                null
            } else {
                workingName(name.substring(0, innerClassStart), mappings)
            }
        } else {
            val newName = mapping.mapped
            "$newName.class"
        }
    }

    private fun classType(name: String, server: FileSystem, mappings: IMappingFile): ClassType {
        val mappedName = workingName(name, mappings)

        return when {
            mappedName == null -> ClassType.Modded
            server.getPath(mappedName).exists() -> ClassType.Server
            else -> ClassType.Client
        }
    }

    private fun collectReferences(path: Path): TypeReferences {
        val node = ClassNode()
        val reader = path.inputStream().use(::ClassReader)
        reader.accept(node, 0)

        val used = buildSet {
            fun tryAdd(type: Type) {
                if (type.sort == Type.ARRAY) {
                    add(type.elementType)
                } else {
                    add(type)
                }
            }

            tryAdd(Type.getObjectType(node.superName))

            for (itf in node.interfaces) {
                tryAdd(Type.getObjectType(itf))
            }

            for (field in node.fields) {
                if (!isClient(field.name, field.visibleAnnotations)) {
                    tryAdd(Type.getType(field.desc))
                }
            }

            for (method in node.methods) {
                if (!isClient(method.name, method.visibleAnnotations)) {
                    for (reference in method.collectTypeReferences()) {
                        tryAdd(reference)
                    }
                }
            }
        }

        val associated = buildSet {
            for (innerClass in node.innerClasses) {
                if (innerClass.name.startsWith(node.name)) {
                    add(Type.getObjectType(innerClass.name))
                }
            }

            val innerEnd = node.name.lastIndexOf('$')
            if (innerEnd != -1) {
                add(Type.getObjectType(node.name.substring(0, innerEnd)))
            }
        }

        return TypeReferences(used, associated)
    }

    private fun isClient(name: String, annotations: List<AnnotationNode>?): Boolean {
        // vanilla srg names will include underscores, so we know this is a vanilla function, and we shouldn't skip it
        if ('_' in name) return false

        if (annotations != null) {
            for (annotation in annotations) {
                if (annotation.desc in annotationNames) {
                    @Suppress("UNCHECKED_CAST")
                    if ((annotation.values[annotation.values.indexOf("value") + 1] as Array<String>)[1] == "CLIENT") {
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun handleServerClass(
        commonPaths: MutableSet<Path>,
        blacklist: MutableSet<Type>,
        modded: Boolean,
        path: Path,
        merged: FileSystem,
        server: FileSystem,
        allInvalid: Boolean,
        mappings: IMappingFile
    ) {
        commonPaths.add(path)

        val typeReferences = collectReferences(path)

        var invalid = allInvalid

        if (!invalid) {
            if (modded) {
                val references = hashSetOf<Type>()

                for (reference in typeReferences.used) {
                    if (reference in blacklist) continue

                    if (reference.sort == Type.OBJECT) {
                        val referenceName = "/${reference.internalName}.class"
                        val newTypePath = merged.getPath(referenceName)

                        if (newTypePath in commonPaths) continue

                        if (newTypePath.exists()) {

                            val type = classType(referenceName, server, mappings)
                            if (type == ClassType.Client) {
                                invalid = true
                                break
                            }

                            references.add(reference)
                        } else {
                            blacklist.add(reference)
                        }
                    } else {
                        blacklist.add(reference)
                    }
                }

                if (!invalid) {
                    for (reference in references) {
                        val referenceName = "/${reference.internalName}.class"
                        val newTypePath = merged.getPath(referenceName)
                        val type = classType(referenceName, server, mappings)
                        handleServerClass(commonPaths, blacklist, type == ClassType.Modded, newTypePath, merged, server, false, mappings)
                    }
                }
            } else {
                for (referencedType in typeReferences.used) {
                    if (referencedType in blacklist) continue

                    if (referencedType.sort == Type.OBJECT) {
                        val referenceName = "/${referencedType.internalName}.class"
                        val newTypePath = merged.getPath(referenceName)

                        if (newTypePath in commonPaths) continue

                        if (newTypePath.exists()) {
                            handleServerClass(commonPaths, blacklist, classType(referenceName, server, mappings) == ClassType.Modded, newTypePath, merged, server, false, mappings)
                        } else {
                            blacklist.add(referencedType)
                        }
                    } else {
                        blacklist.add(referencedType)
                    }
                }
            }
        }

        // We don't care if this class is vanilla or not when handling the classed directly connected.
        for (reference in typeReferences.associated) {
            val referenceName = "/${reference.internalName}.class"
            val newTypePath = merged.getPath(referenceName)

            if (newTypePath in commonPaths) continue

            if (newTypePath.exists()) {
                val type = classType(referenceName, server, mappings)
                handleServerClass(commonPaths, blacklist, type == ClassType.Modded, newTypePath, merged, server, invalid, mappings)
            } else {
                blacklist.add(reference)
            }
        }
    }

    fun splitJars(
        merged: FileSystem,
        client: FileSystem,
        server: FileSystem,
        commonOut: FileSystem,
        clientOut: FileSystem,
        mappings: IMappingFile,
    ) {
        val blacklist = hashSetOf<Type>()
        val commonPaths = hashSetOf<Path>()

        for (path in Files.walk(merged.getPath("/")).filter(Path::isRegularFile)) {
            if (path in commonPaths) continue

            val name = path.toString()

            if (name.contains('-') || !name.endsWith(".class")) continue

            val type = classType(name, server, mappings)

            if (type == ClassType.Modded) {
/*                var isCommon = true

                for (reference in collectReferences(path)) {
                    if (reference in blacklist) continue

                    if (reference.sort == Type.OBJECT) {
                        if (classType("/${reference.internalName}.class", server, mappings, mappings) == ClassType.Client) {
                            isCommon = false
                            break
                        }
                    } else {
                        blacklist.add(reference)
                    }
                }

                if (isCommon) {
                    commonPaths.add(path)
                }*/

                // TODO probably find a different way to do this than checking if it contains a string
                if ("client" !in name) {
                    commonPaths.add(path)
                }

                continue
            }

            if (type == ClassType.Server) {
                handleServerClass(commonPaths, blacklist, false, path, merged, server, false, mappings)
            }
        }

        val clientPaths = merged.getPath("/").walk { filter(Path::isRegularFile).filter { it.toString().endsWith(".class") && !it.toString().contains('-') && !commonPaths.contains(it) }.toList() }

        for (path in commonPaths) {
            val path1 = commonOut.getPath(path.toString())
            path1.parent?.createDirectories()
            path.copyTo(path1)
        }

        for (path in clientPaths) {
            val path1 = clientOut.getPath(path.toString())
            path1.parent?.createDirectories()
            path.copyTo(path1)
        }

        copyAssets(client, server, commonOut, clientOut)

        val mergedRoot = merged.getPath("/")
        merged.withAssets { path ->
            if ("lang" !in path.toString()) {
                var root: Path? = path
                while (root?.parent != mergedRoot) {
                    root = root?.parent
                }

                if (root == path) root = null

                if (root != null && clientOut.getPath(root.toString()).exists()) {
                    val newPath = clientOut.getPath(path.toString())
                    newPath.parent?.createDirectories()
                    path.copyTo(newPath, StandardCopyOption.REPLACE_EXISTING)
                    return@withAssets
                }
            }

            val newPath = commonOut.getPath(path.toString())
            newPath.parent?.createDirectories()
            path.copyTo(newPath, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /**
     * @param used All the class references used inside this type
     * @param associated The outer class and all the inner classes
     */
    data class TypeReferences(val used: Set<Type>, val associated: Set<Type>)

    enum class ClassType {
        Client, // From the vanilla client Jar
        Server, // From the vanilla server Jar
        Modded // From an external source, like Forge's universal Jar or userdev injects
    }
}
