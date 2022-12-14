package net.msrandom.minecraftcodev.forge.mappings

import de.siegmar.fastcsv.reader.NamedCsvReader
import kotlinx.serialization.json.decodeFromStream
import net.fabricmc.mappingio.MappedElementKind
import net.fabricmc.mappingio.MappingUtil
import net.fabricmc.mappingio.adapter.MappingNsCompleter
import net.fabricmc.mappingio.format.ProGuardReader
import net.fabricmc.mappingio.format.TsrgReader
import net.fabricmc.mappingio.tree.MemoryMappingTree
import net.msrandom.minecraftcodev.core.MappingsNamespace
import net.msrandom.minecraftcodev.core.MinecraftCodevExtension
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin.Companion.unsafeResolveConfiguration
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin.Companion.zipFileSystem
import net.msrandom.minecraftcodev.core.MinecraftType
import net.msrandom.minecraftcodev.core.repository.MinecraftRepositoryImpl
import net.msrandom.minecraftcodev.forge.McpConfig
import net.msrandom.minecraftcodev.forge.MinecraftCodevForgePlugin
import net.msrandom.minecraftcodev.forge.UserdevConfig
import net.msrandom.minecraftcodev.forge.dependency.PatchedComponentIdentifier
import net.msrandom.minecraftcodev.forge.resolve.PatchedMinecraftComponentResolvers
import net.msrandom.minecraftcodev.remapper.MinecraftCodevRemapperPlugin
import net.msrandom.minecraftcodev.remapper.RemapperExtension
import org.cadixdev.at.io.AccessTransformFormats
import org.cadixdev.lorenz.MappingSet
import org.gradle.api.Project
import org.gradle.api.internal.artifacts.RepositoriesSupplier
import org.gradle.api.internal.artifacts.configurations.ResolutionStrategyInternal
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.StartParameterResolutionOverride
import org.gradle.api.internal.artifacts.ivyservice.modulecache.FileStoreAndIndexProvider
import org.gradle.configurationcache.extensions.serviceOf
import org.gradle.internal.hash.ChecksumService
import org.objectweb.asm.*
import java.io.InputStream
import java.nio.file.Path
import java.util.jar.Manifest
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.notExists

internal fun Project.setupForgeRemapperIntegration() {
    plugins.withType(MinecraftCodevRemapperPlugin::class.java) {
        val fileStoreAndIndexProvider = serviceOf<FileStoreAndIndexProvider>()
        val checksumService = serviceOf<ChecksumService>()
        val repositoriesSupplier = serviceOf<RepositoriesSupplier>()
        val startParameterResolutionOverride = serviceOf<StartParameterResolutionOverride>()
        val remapper = extensions.getByType(MinecraftCodevExtension::class.java).extensions.getByType(RemapperExtension::class.java)

        remapper.zipMappingsResolution.add { path, fileSystem, visitor, configuration, decorate, existingMappings, _, objects ->
            val configPath = fileSystem.getPath("config.json")
            if (configPath.exists()) {
                val mcpDependency = dependencies.create(configPath.inputStream().use { MinecraftCodevPlugin.json.decodeFromStream<UserdevConfig>(it) }.mcp)
                val mcp = unsafeResolveConfiguration(configurations.detachedConfiguration(mcpDependency).setTransitive(false)).singleFile

                zipFileSystem(mcp.toPath()).use { mcpFs ->
                    val config = mcpFs.getPath("config.json").inputStream().decorate().use { MinecraftCodevPlugin.json.decodeFromStream<McpConfig>(it) }
                    val mappings = mcpFs.getPath(config.data.mappings)

                    val namespaceCompleter = MappingNsCompleter(visitor, mapOf(MinecraftCodevRemapperPlugin.NAMED_MAPPINGS_NAMESPACE to MinecraftCodevForgePlugin.SRG_MAPPINGS_NAMESPACE), true)

                    val classFixer = if (config.official) {
                        val clientMappingsDependency = extensions.getByType(MinecraftCodevExtension::class.java)(MinecraftType.ClientMappings, config.version)
                        val clientMappingsFile = unsafeResolveConfiguration(configurations.detachedConfiguration(clientMappingsDependency)).singleFile
                        val clientMappings = MemoryMappingTree()

                        clientMappingsFile.reader().use { ProGuardReader.read(it, clientMappings) }
                        ClassNameReplacer(namespaceCompleter, clientMappings, MinecraftCodevForgePlugin.SRG_MAPPINGS_NAMESPACE, MappingUtil.NS_TARGET_FALLBACK)
                    } else {
                        namespaceCompleter
                    }

                    mappings.inputStream().reader().use {
                        TsrgReader.read(
                            it, MappingsNamespace.OBF, MinecraftCodevForgePlugin.SRG_MAPPINGS_NAMESPACE, classFixer
                        )
                    }

                    if (!config.official) {
                        val patchState = PatchedMinecraftComponentResolvers.getPatchState(
                            PatchedComponentIdentifier("forge", config.version, "", null),
                            repositoriesSupplier.get().filterIsInstance<MinecraftRepositoryImpl>().map(MinecraftRepositoryImpl::createResolver),
                            MinecraftCodevPlugin.getCacheProvider(gradle),
                            (configuration.resolutionStrategy as ResolutionStrategyInternal).cachePolicy.also(startParameterResolutionOverride::applyToCachePolicy),
                            fileStoreAndIndexProvider,
                            checksumService,
                            path.toFile(),
                            objects
                        ) { _, _ -> false } ?: return@add false

                        zipFileSystem(patchState.withAssets).use { patchedJar ->
                            if (visitor.visitHeader()) {
                                if (visitor.visitContent()) {
                                    val obfNamespace = existingMappings.getNamespaceId(MappingsNamespace.OBF)
                                    val srgNamespace = existingMappings.getNamespaceId(MinecraftCodevForgePlugin.SRG_MAPPINGS_NAMESPACE)
                                    val namedNamespace = existingMappings.getNamespaceId(MinecraftCodevRemapperPlugin.NAMED_MAPPINGS_NAMESPACE)

                                    for (classMapping in existingMappings.classes) {
                                        if (visitor.visitClass(classMapping.getName(obfNamespace))) {
                                            val classPath = patchedJar.getPath(classMapping.getName(srgNamespace) + ".class")
                                            if (classPath.notExists()) continue

                                            val reader = classPath.inputStream().use(::ClassReader)
                                            reader.accept(object : ClassVisitor(Opcodes.ASM9) {
                                                override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor {
                                                    val methodMapping = classMapping.getMethod(name, descriptor, srgNamespace)
                                                    val arguments = Type.getMethodType(descriptor).argumentTypes
                                                    val argumentStack = arguments.toMutableList()

                                                    if (methodMapping == null) {
                                                        return if (visitor.visitMethod(name, descriptor)) {
                                                            visitor.visitDstName(MappedElementKind.METHOD, srgNamespace, name)
                                                            visitor.visitDstName(MappedElementKind.METHOD, namedNamespace, name)

                                                            object : MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, descriptor, signature, exceptions)) {
                                                                override fun visitLocalVariable(name: String?, descriptor: String, signature: String?, start: Label, end: Label, index: Int) {
                                                                    if (argumentStack.isEmpty() || name == null) {
                                                                        super.visitLocalVariable(name, descriptor, signature, start, end, index)
                                                                    } else {
                                                                        if (Type.getType(descriptor) == argumentStack[0]) {
                                                                            argumentStack.removeAt(0)
                                                                            visitor.visitMethodArg(arguments.size - argumentStack.size, index, null)
                                                                            visitor.visitDstName(MappedElementKind.METHOD_ARG, srgNamespace, name)
                                                                            visitor.visitDstName(MappedElementKind.METHOD_ARG, namedNamespace, name)
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                        } else {
                                                            super.visitMethod(access, name, descriptor, signature, exceptions)
                                                        }
                                                    } else {
                                                        return if (visitor.visitMethod(methodMapping.getName(obfNamespace), methodMapping.getDesc(obfNamespace))) {
                                                            visitor.visitDstName(MappedElementKind.METHOD, srgNamespace, name)
                                                            visitor.visitDstName(MappedElementKind.METHOD, namedNamespace, name)

                                                            object : MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, descriptor, signature, exceptions)) {
                                                                override fun visitLocalVariable(name: String?, descriptor: String, signature: String?, start: Label, end: Label, index: Int) {
                                                                    if (argumentStack.isEmpty() || name == null) {
                                                                        super.visitLocalVariable(name, descriptor, signature, start, end, index)
                                                                    } else {
                                                                        if (Type.getType(descriptor) == argumentStack[0]) {
                                                                            argumentStack.removeAt(0)

                                                                            visitor.visitMethodArg(
                                                                                arguments.size - argumentStack.size,
                                                                                index,
                                                                                methodMapping.args.firstOrNull { it.lvIndex == index }?.getName(obfNamespace)
                                                                            )

                                                                            visitor.visitDstName(MappedElementKind.METHOD_ARG, srgNamespace, name)
                                                                            visitor.visitDstName(MappedElementKind.METHOD_ARG, namedNamespace, name)
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                        } else {
                                                            super.visitMethod(access, name, descriptor, signature, exceptions)
                                                        }
                                                    }
                                                }
                                            }, 0)
                                        }
                                    }
                                }
                            }

                            visitor.visitEnd()
                        }
                    }
                }

                true
            } else {
                false
            }
        }

        remapper.zipMappingsResolution.add { _, fileSystem, visitor, _, decorate, existingMappings, _, _ ->
            val methods = fileSystem.getPath("methods.csv")
            val fields = fileSystem.getPath("fields.csv")

            // We just need one of those to assume these are MCP mappings.
            if (methods.exists() || fields.exists()) {
                val params = fileSystem.getPath("params.csv")

                val methodsMap = readMcp(methods, "searge", decorate)
                val fieldsMap = readMcp(fields, "searge", decorate)
                val paramsMap = readMcp(params, "param", decorate)

                val sourceNamespace = existingMappings.getNamespaceId(MinecraftCodevForgePlugin.SRG_MAPPINGS_NAMESPACE)
                val targetNamespace = existingMappings.getNamespaceId(MinecraftCodevRemapperPlugin.NAMED_MAPPINGS_NAMESPACE)

                do {
                    if (visitor.visitContent()) {
                        for (classMapping in existingMappings.classes.toList()) {
                            visitor.visitClass(classMapping.srcName)

                            if (visitor.visitElementContent(MappedElementKind.CLASS)) {
                                if (fieldsMap.isNotEmpty()) {
                                    for (field in classMapping.fields.toList()) {
                                        val name = field.getName(sourceNamespace)
                                        val mapping = fieldsMap[name]

                                        visitor.visitField(field.srcName, field.srcDesc)

                                        if (mapping != null) {
                                            visitor.visitDstName(MappedElementKind.FIELD, targetNamespace, mapping.name)

                                            if (mapping.comment != null) {
                                                visitor.visitComment(MappedElementKind.FIELD, mapping.comment)
                                            }
                                        }
                                    }
                                }

                                if (methodsMap.isNotEmpty() || paramsMap.isNotEmpty()) {
                                    for (method in classMapping.methods.toList()) {
                                        if (methodsMap.isNotEmpty()) {
                                            val name = method.getName(sourceNamespace)
                                            val mapping = methodsMap[name]

                                            visitor.visitMethod(method.srcName, method.srcDesc)

                                            if (mapping != null) {
                                                visitor.visitDstName(MappedElementKind.METHOD, targetNamespace, mapping.name)

                                                if (mapping.comment != null) {
                                                    visitor.visitComment(MappedElementKind.METHOD, mapping.comment)
                                                }
                                            }
                                        }

                                        if (paramsMap.isNotEmpty()) {
                                            for (argument in method.args.toList()) {
                                                val name = argument.getName(sourceNamespace)
                                                val mapping = paramsMap[name]

                                                visitor.visitMethodArg(argument.argPosition, argument.lvIndex, argument.srcName)

                                                if (mapping != null) {
                                                    visitor.visitDstName(MappedElementKind.METHOD_ARG, targetNamespace, mapping.name)

                                                    if (mapping.comment != null) {
                                                        visitor.visitComment(MappedElementKind.METHOD_ARG, mapping.comment)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } while (!visitor.visitEnd())

                true
            } else {
                false
            }
        }

        remapper.extraFileRemappers.add { mappings, directory, sourceNamespaceId, targetNamespaceId ->
            val manifest = directory.resolve("META-INF/MANIFEST.MF").inputStream().use(::Manifest)
            val accessTransformerName = manifest.mainAttributes.getValue("FMLAT") ?: "accesstransformer.cfg"
            val path = directory.resolve("META-INF/$accessTransformerName")

            if (path.exists()) {
                val mappingSet = MappingSet.create()

                for (treeClass in mappings.classes) {
                    val className = treeClass.getName(sourceNamespaceId)
                    val mapping = mappingSet.getOrCreateClassMapping(className)
                        .setDeobfuscatedName(treeClass.getName(targetNamespaceId))

                    for (field in treeClass.fields) {
                        val name = field.getName(sourceNamespaceId)
                        val descriptor = field.getDesc(sourceNamespaceId)

                        val fieldMapping = if (descriptor == null) {
                            mapping.getOrCreateFieldMapping(name)
                        } else {
                            mapping.getOrCreateFieldMapping(name, descriptor)
                        }

                        fieldMapping.deobfuscatedName = field.getName(targetNamespaceId)
                    }

                    for (method in treeClass.methods) {
                        val name = method.getName(sourceNamespaceId)
                        val methodMapping = mapping.getOrCreateMethodMapping(name, method.getDesc(sourceNamespaceId))
                            .setDeobfuscatedName(method.getName(targetNamespaceId))

                        for (argument in method.args) {
                            methodMapping.getOrCreateParameterMapping(argument.argPosition).deobfuscatedName = argument.getName(targetNamespaceId)
                        }
                    }
                }

                val accessTransformer = AccessTransformFormats.FML.read(path)
                path.deleteExisting()

                AccessTransformFormats.FML.write(path, accessTransformer.remap(mappingSet))
            }
        }
    }
}

private fun readMcp(path: Path, source: String, decorate: InputStream.() -> InputStream) =
    path.takeIf(Path::exists)?.inputStream()?.decorate()?.reader()?.let { NamedCsvReader.builder().build(it) }?.use {
        buildMap {
            if (it.header.contains("desc")) {
                for (row in it.stream()) {
                    put(row.getField(source), McpMapping(row.getField("name"), row.getField("desc")))
                }
            } else {
                for (row in it.stream()) {
                    put(row.getField(source), McpMapping(row.getField("name")))
                }
            }
        }
    } ?: emptyMap()

private data class McpMapping(val name: String, val comment: String? = null)
