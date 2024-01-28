package net.msrandom.minecraftcodev.core.task

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.objectweb.asm.*
import java.io.File
import java.net.URLClassLoader
import kotlin.io.path.createDirectories

data class MethodId(val name: String, val descriptor: String)

class CachedClientOnlyMembers(val isClientOnlyType: Boolean, val interfaces: List<String>, val methods: List<MethodId>)

interface IsClientOnlyAnnotation {
    val isClientOnlyAnnotation: Boolean
}

interface AnnotationVisitorProvider<T, U> where T : AnnotationVisitor, T : IsClientOnlyAnnotation {
    val id: U
    val annotationVisitor: T?
}

abstract class AddClientSideAnnotations<T> : DefaultTask() where T : AnnotationVisitor, T : IsClientOnlyAnnotation {
    abstract val directory: DirectoryProperty
        @InputDirectory get

    abstract val destination: DirectoryProperty
        @OutputDirectory get

    abstract val classpath: ConfigurableFileCollection
        @InputFiles get

    abstract fun addClientOnlyAnnotation(visitor: ClassVisitor, interfaces: List<String>)
    abstract fun addClientOnlyAnnotation(visitor: MethodVisitor)
    abstract fun getClientOnlyAnnotationVisitor(descriptor: String, visitor: AnnotationVisitor?): T?

    fun getClientOnlyMembers(
        classpath: URLClassLoader,
        type: String,
        cache: MutableMap<String, CachedClientOnlyMembers>,
        handled: MutableSet<File>
    ): CachedClientOnlyMembers? {
        if (type in cache) {
            return cache.getValue(type)
        }

        val name = "$type.class"
        val file = directory.get().file(name).asFile

        val addAnnotations: Boolean

        val stream = if (file.exists()) {
            addAnnotations = true

            handled.add(file)

            file.inputStream()
        } else {
            addAnnotations = false

            classpath.getResourceAsStream(name)
        }

        if (stream == null) {
            return null
        }

        val reader = ClassReader(stream)
        val writer = if (addAnnotations) ClassWriter(0) else null

        val visitor = object : ClassVisitor(Opcodes.ASM9, writer) {
            val methods = mutableListOf<AnnotationVisitorProvider<T, MethodId>>()

            lateinit var cachedMembers: CachedClientOnlyMembers
            var annotationVisitor: T? = null

            override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? {
                val visitor = super.visitAnnotation(descriptor, visible)

                annotationVisitor = getClientOnlyAnnotationVisitor(descriptor, visitor) ?: return visitor

                return annotationVisitor
            }

            override fun visit(version: Int, access: Int, name: String, signature: String?, superName: String?, interfaces: Array<out String>?) {
                val superInterfaces = mutableListOf<String>()

                val superMethods = superName?.let { getClientOnlyMembers(classpath, it, cache, handled)?.methods } ?: emptyList()

                val methods = ArrayList(superMethods)

                if (interfaces != null) {
                    for (superInterface in interfaces) {
                        val members = getClientOnlyMembers(classpath, superInterface, cache, handled) ?: continue

                        if (members.isClientOnlyType) {
                            superInterfaces.add(superInterface)
                        }

                        methods.addAll(members.methods)
                    }
                }

                if (superInterfaces.isNotEmpty()) {
                    addClientOnlyAnnotation(this, superInterfaces)
                }

                cachedMembers = CachedClientOnlyMembers(false, superInterfaces, methods)

                super.visit(version, access, name, signature, superName, interfaces)
            }

            override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor {
                val methodId = MethodId(name, descriptor)

                val visitor = object : MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, descriptor, signature, exceptions)), AnnotationVisitorProvider<T, MethodId> {
                    override val id get() = methodId
                    override var annotationVisitor: T? = null

                    override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? {
                        val visitor = super.visitAnnotation(descriptor, visible)

                        annotationVisitor = getClientOnlyAnnotationVisitor(descriptor, visitor) ?: return visitor

                        return annotationVisitor
                    }
                }

                methods.add(visitor)

                if (addAnnotations && methodId in cachedMembers.methods) {
                    addClientOnlyAnnotation(visitor)
                }

                return visitor
            }
        }

        reader.accept(visitor, 0)

        val result = CachedClientOnlyMembers(
            visitor.annotationVisitor?.isClientOnlyAnnotation == true,
            visitor.cachedMembers.interfaces,
            visitor.cachedMembers.methods +
                    visitor.methods
                        .filter { it.annotationVisitor?.isClientOnlyAnnotation == true }
                        .map { it.id }
        )

        if (writer != null) {
            val output = destination.file(file.relativeTo(directory.asFile.get()).path).get().asFile

            output.toPath().parent.createDirectories()
            output.writeBytes(writer.toByteArray())
        }

        return result
    }

    @TaskAction
    fun addAnnotations() {
        val cachedClientOnlyMembers = hashMapOf<String, CachedClientOnlyMembers>()
        val handled = hashSetOf<File>()

        val classpath = URLClassLoader(classpath.map { it.toURI().toURL() }.toTypedArray())

        for (file in directory.get().asFileTree) {
            if (!file.name.endsWith(".class")) continue
            if (file in handled) continue

            val className = file.path.substring(0, file.path.length - ".class".length)

            getClientOnlyMembers(classpath, className, cachedClientOnlyMembers, handled)
        }
    }
}
