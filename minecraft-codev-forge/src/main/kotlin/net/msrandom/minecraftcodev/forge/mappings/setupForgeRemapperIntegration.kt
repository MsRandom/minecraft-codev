package net.msrandom.minecraftcodev.forge.mappings

import de.siegmar.fastcsv.reader.NamedCsvReader
import kotlinx.serialization.json.decodeFromStream
import net.fabricmc.mappingio.MappedElementKind
import net.fabricmc.mappingio.MappingUtil
import net.fabricmc.mappingio.adapter.ForwardingMappingVisitor
import net.fabricmc.mappingio.adapter.MappingNsCompleter
import net.fabricmc.mappingio.format.ProGuardReader
import net.fabricmc.mappingio.format.TsrgReader
import net.fabricmc.mappingio.tree.MappingTreeView
import net.fabricmc.mappingio.tree.MemoryMappingTree
import net.msrandom.minecraftcodev.core.*
import net.msrandom.minecraftcodev.core.dependency.resolverFactories
import net.msrandom.minecraftcodev.core.repository.MinecraftRepositoryImpl
import net.msrandom.minecraftcodev.core.resolve.MinecraftArtifactResolver
import net.msrandom.minecraftcodev.core.resolve.MinecraftComponentIdentifier
import net.msrandom.minecraftcodev.core.utils.extension
import net.msrandom.minecraftcodev.core.utils.getCacheProvider
import net.msrandom.minecraftcodev.core.utils.zipFileSystem
import net.msrandom.minecraftcodev.forge.*
import net.msrandom.minecraftcodev.forge.accesswidener.findAccessTransformers
import net.msrandom.minecraftcodev.forge.dependency.PatchedComponentIdentifier
import net.msrandom.minecraftcodev.forge.resolve.PatchedMinecraftComponentResolvers
import net.msrandom.minecraftcodev.forge.resolve.PatchedSetupState
import net.msrandom.minecraftcodev.remapper.MappingResolutionData
import net.msrandom.minecraftcodev.remapper.MinecraftCodevRemapperPlugin
import net.msrandom.minecraftcodev.remapper.RemapperExtension
import net.msrandom.minecraftcodev.remapper.dependency.getNamespaceId
import net.msrandom.minecraftcodev.remapper.dependency.remapped
import org.cadixdev.at.io.AccessTransformFormats
import org.cadixdev.lorenz.MappingSet
import org.gradle.api.Project
import org.gradle.api.internal.artifacts.GlobalDependencyResolutionRules
import org.gradle.api.internal.artifacts.RepositoriesSupplier
import org.gradle.api.internal.artifacts.ResolveContext
import org.gradle.api.internal.artifacts.configurations.ResolutionStrategyInternal
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.ComponentResolvers
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.ResolveIvyFactory
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.StartParameterResolutionOverride
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.ComponentResolversChain
import org.gradle.api.internal.attributes.ImmutableAttributes
import org.gradle.configurationcache.extensions.serviceOf
import org.gradle.internal.component.external.model.DefaultModuleComponentArtifactMetadata
import org.gradle.internal.component.model.DefaultComponentOverrideMetadata
import org.gradle.internal.component.model.DefaultIvyArtifactName
import org.gradle.internal.hash.ChecksumService
import org.gradle.internal.resolve.result.DefaultBuildableArtifactResolveResult
import org.gradle.internal.resolve.result.DefaultBuildableComponentResolveResult
import org.gradle.util.internal.BuildCommencedTimeProvider
import org.objectweb.asm.*
import java.nio.file.Path
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.notExists

internal fun Project.setupForgeRemapperIntegration() {
    plugins.withType(MinecraftCodevRemapperPlugin::class.java) {
        val checksumService = serviceOf<ChecksumService>()
        val timeProvider = serviceOf<BuildCommencedTimeProvider>()
        val repositoriesSupplier = serviceOf<RepositoriesSupplier>()
        val startParameterResolutionOverride = serviceOf<StartParameterResolutionOverride>()
        val remapper = extensions.getByType(MinecraftCodevExtension::class.java).extensions.getByType(RemapperExtension::class.java)

        remapper.zipMappingsResolution.add { path, fileSystem, _, data ->
            val configPath = fileSystem.getPath("config.json")

            if (configPath.exists()) {
                val mcpLibrary = configPath.inputStream().use { MinecraftCodevPlugin.json.decodeFromStream<UserdevConfig>(it) }.mcp
                val resolvers by lazy {
                    val detachedConfiguration = configurations.detachedConfiguration()
                    val resolutionStrategy = detachedConfiguration.resolutionStrategy
                    val resolvers = mutableListOf<ComponentResolvers>()
                    for (resolverFactory in gradle.resolverFactories) {
                        resolverFactory.create(detachedConfiguration as ResolveContext, resolvers)
                    }

                    resolvers.add(
                        serviceOf<ResolveIvyFactory>().create(
                            detachedConfiguration.name,
                            resolutionStrategy as ResolutionStrategyInternal,
                            serviceOf<RepositoriesSupplier>().get(),
                            project.serviceOf<GlobalDependencyResolutionRules>().componentMetadataProcessorFactory,
                            ImmutableAttributes.EMPTY,
                            null,
                            serviceOf(),
                            serviceOf(),
                        ),
                    )

                    ComponentResolversChain(resolvers, serviceOf(), serviceOf(), serviceOf())
                }

                val mcp = PatchedSetupState.resolveArtifact({ resolvers }, mcpLibrary)

                zipFileSystem(mcp.toPath()).use { mcpFs ->
                    val config =
                        data.decorate(mcpFs.base.getPath("config.json").inputStream()).use {
                            MinecraftCodevPlugin.json.decodeFromStream<McpConfig>(it)
                        }
                    val mappings = mcpFs.base.getPath(config.data.mappings)

                    val namespaceCompleter =
                        MappingNsCompleter(
                            data.visitor.tree,
                            mapOf(
                                MinecraftCodevRemapperPlugin.NAMED_MAPPINGS_NAMESPACE to MinecraftCodevForgePlugin.SRG_MAPPINGS_NAMESPACE,
                            ),
                            true,
                        )

                    val classFixer =
                        if (config.official) {
                            val componentResult = DefaultBuildableComponentResolveResult()
                            val artifactResult = DefaultBuildableArtifactResolveResult()

                            val componentId = MinecraftComponentIdentifier(MinecraftType.ClientMappings.module, config.version)

                            val repositories =
                                repositoriesSupplier.get()
                                    .filterIsInstance<MinecraftRepositoryImpl>()
                                    .map(MinecraftRepositoryImpl::createResolver)

                            resolvers.componentResolver.resolve(componentId, DefaultComponentOverrideMetadata.EMPTY, componentResult)

                            data.objectFactory.newInstance(MinecraftArtifactResolver::class.java, repositories).resolveArtifact(
                                componentResult.state.prepareForArtifactResolution().resolveMetadata,
                                DefaultModuleComponentArtifactMetadata(
                                    componentId,
                                    DefaultIvyArtifactName(
                                        MinecraftType.ClientMappings.module,
                                        "txt",
                                        "txt",
                                    ),
                                ),
                                artifactResult,
                            )

                            val clientMappings = MemoryMappingTree()

                            artifactResult.result.file.reader().use { ProGuardReader.read(it, clientMappings) }
                            ClassNameReplacer(
                                namespaceCompleter,
                                clientMappings,
                                MinecraftCodevForgePlugin.SRG_MAPPINGS_NAMESPACE,
                                MappingUtil.NS_SOURCE_FALLBACK,
                                MappingUtil.NS_TARGET_FALLBACK,
                            )
                        } else {
                            namespaceCompleter
                        }

                    mappings.inputStream().reader().use {
                        TsrgReader.read(
                            it,
                            MappingsNamespace.OBF,
                            MinecraftCodevForgePlugin.SRG_MAPPINGS_NAMESPACE,
                            classFixer,
                        )
                    }

                    if (!config.official) {
                        val patched =
                            PatchedMinecraftComponentResolvers.getPatchedOutput(
                                PatchedComponentIdentifier(config.version, data.configuration.name),
                                repositoriesSupplier.get().filterIsInstance<MinecraftRepositoryImpl>().map(
                                    MinecraftRepositoryImpl::createResolver,
                                ),
                                getCacheProvider(gradle),
                                (data.configuration.resolutionStrategy as ResolutionStrategyInternal).cachePolicy.also(
                                    startParameterResolutionOverride::applyToCachePolicy,
                                ),
                                checksumService,
                                timeProvider,
                                Userdev.fromFile(path.toFile())!!,
                                resolvers,
                                project,
                                data.objectFactory,
                            ) ?: return@add false

                        zipFileSystem(patched.toPath()).use { patchedJar ->
                            val visitor = data.visitor.tree

                            if (visitor.visitHeader() && visitor.visitContent()) {
                                val obfNamespace = visitor.getNamespaceId(MappingsNamespace.OBF)
                                val srgNamespace = visitor.getNamespaceId(MinecraftCodevForgePlugin.SRG_MAPPINGS_NAMESPACE)
                                val namedNamespace = visitor.getNamespaceId(MinecraftCodevRemapperPlugin.NAMED_MAPPINGS_NAMESPACE)

                                for (classMapping in (visitor as MappingTreeView).classes) {
                                    if (visitor.visitClass(classMapping.getName(obfNamespace))) {
                                        val classPath = patchedJar.base.getPath(classMapping.getName(srgNamespace) + ".class")
                                        if (classPath.notExists()) continue

                                        val reader = classPath.inputStream().use(::ClassReader)
                                        reader.accept(
                                            object : ClassVisitor(Opcodes.ASM9) {
                                                override fun visitMethod(
                                                    access: Int,
                                                    name: String,
                                                    descriptor: String,
                                                    signature: String?,
                                                    exceptions: Array<out String>?,
                                                ): MethodVisitor {
                                                    val methodMapping = classMapping.getMethod(name, descriptor, srgNamespace)
                                                    val arguments = Type.getMethodType(descriptor).argumentTypes
                                                    val argumentStack = arguments.toMutableList()

                                                    if (methodMapping == null) {
                                                        return if (visitor.visitMethod(name, descriptor)) {
                                                            visitor.visitDstName(MappedElementKind.METHOD, srgNamespace, name)
                                                            visitor.visitDstName(MappedElementKind.METHOD, namedNamespace, name)

                                                            object : MethodVisitor(
                                                                Opcodes.ASM9,
                                                                super.visitMethod(access, name, descriptor, signature, exceptions),
                                                            ) {
                                                                override fun visitLocalVariable(
                                                                    name: String?,
                                                                    descriptor: String,
                                                                    signature: String?,
                                                                    start: Label,
                                                                    end: Label,
                                                                    index: Int,
                                                                ) {
                                                                    if (argumentStack.isEmpty() || name == null) {
                                                                        super.visitLocalVariable(
                                                                            name,
                                                                            descriptor,
                                                                            signature,
                                                                            start,
                                                                            end,
                                                                            index,
                                                                        )
                                                                    } else {
                                                                        if (Type.getType(descriptor) == argumentStack[0]) {
                                                                            argumentStack.removeAt(0)
                                                                            visitor.visitMethodArg(
                                                                                arguments.size - argumentStack.size,
                                                                                index,
                                                                                null,
                                                                            )
                                                                            visitor.visitDstName(
                                                                                MappedElementKind.METHOD_ARG,
                                                                                srgNamespace,
                                                                                name,
                                                                            )
                                                                            visitor.visitDstName(
                                                                                MappedElementKind.METHOD_ARG,
                                                                                namedNamespace,
                                                                                name,
                                                                            )
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                        } else {
                                                            super.visitMethod(access, name, descriptor, signature, exceptions)
                                                        }
                                                    } else {
                                                        return if (visitor.visitMethod(
                                                                methodMapping.getName(obfNamespace),
                                                                methodMapping.getDesc(obfNamespace),
                                                            )
                                                        ) {
                                                            visitor.visitDstName(MappedElementKind.METHOD, srgNamespace, name)
                                                            visitor.visitDstName(MappedElementKind.METHOD, namedNamespace, name)

                                                            object : MethodVisitor(
                                                                Opcodes.ASM9,
                                                                super.visitMethod(access, name, descriptor, signature, exceptions),
                                                            ) {
                                                                override fun visitLocalVariable(
                                                                    name: String?,
                                                                    descriptor: String,
                                                                    signature: String?,
                                                                    start: Label,
                                                                    end: Label,
                                                                    index: Int,
                                                                ) {
                                                                    if (argumentStack.isEmpty() || name == null) {
                                                                        super.visitLocalVariable(
                                                                            name,
                                                                            descriptor,
                                                                            signature,
                                                                            start,
                                                                            end,
                                                                            index,
                                                                        )
                                                                    } else {
                                                                        if (Type.getType(descriptor) == argumentStack[0]) {
                                                                            argumentStack.removeAt(0)

                                                                            visitor.visitMethodArg(
                                                                                arguments.size - argumentStack.size,
                                                                                index,
                                                                                methodMapping.args.firstOrNull { it.lvIndex == index }?.getName(
                                                                                    obfNamespace,
                                                                                ),
                                                                            )

                                                                            visitor.visitDstName(
                                                                                MappedElementKind.METHOD_ARG,
                                                                                srgNamespace,
                                                                                name,
                                                                            )
                                                                            visitor.visitDstName(
                                                                                MappedElementKind.METHOD_ARG,
                                                                                namedNamespace,
                                                                                name,
                                                                            )
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                        } else {
                                                            super.visitMethod(access, name, descriptor, signature, exceptions)
                                                        }
                                                    }
                                                }
                                            },
                                            0,
                                        )
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

        remapper.zipMappingsResolution.add { _, fileSystem, _, data ->
            val methods = fileSystem.getPath("methods.csv")
            val fields = fileSystem.getPath("fields.csv")

            // We just need one of those to assume these are MCP mappings.
            if (!methods.exists() && !fields.exists()) {
                return@add false
            }
            val params = fileSystem.getPath("params.csv")

            val methodsMap = readMcp(methods, "searge", data)
            val fieldsMap = readMcp(fields, "searge", data)
            val paramsMap = readMcp(params, "param", data)

            data.visitor.withTree(MinecraftCodevForgePlugin.SRG_MAPPINGS_NAMESPACE) { tree ->
                tree.accept(
                    object : ForwardingMappingVisitor(data.visitor.tree) {
                        private var targetNamespace = MappingTreeView.NULL_NAMESPACE_ID

                        override fun visitNamespaces(
                            srcNamespace: String,
                            dstNamespaces: List<String>,
                        ) {
                            super.visitNamespaces(srcNamespace, dstNamespaces)

                            targetNamespace =
                                MinecraftCodevRemapperPlugin.NAMED_MAPPINGS_NAMESPACE.getNamespaceId(
                                    srcNamespace,
                                    dstNamespaces,
                                )
                        }

                        override fun visitClass(srcName: String) = true

                        override fun visitField(
                            srcName: String,
                            srcDesc: String?,
                        ): Boolean {
                            if (!super.visitField(srcName, srcDesc)) {
                                return false
                            }

                            fieldsMap[srcName]?.let { fieldMapping ->
                                visitDstName(MappedElementKind.FIELD, targetNamespace, fieldMapping.name)

                                fieldMapping.comment?.let { it ->
                                    visitComment(MappedElementKind.FIELD, it)
                                }
                            }

                            return true
                        }

                        override fun visitMethod(
                            srcName: String,
                            srcDesc: String,
                        ): Boolean {
                            if (!super.visitMethod(srcName, srcDesc)) {
                                return false
                            }

                            methodsMap[srcName]?.let { methodMapping ->
                                visitDstName(MappedElementKind.METHOD, targetNamespace, methodMapping.name)

                                methodMapping.comment?.let { it ->
                                    visitComment(MappedElementKind.METHOD, it)
                                }
                            }

                            return true
                        }

                        override fun visitMethodArg(
                            argPosition: Int,
                            lvIndex: Int,
                            srcName: String?,
                        ): Boolean {
                            if (!super.visitMethodArg(argPosition, lvIndex, srcName)) {
                                return false
                            }

                            srcName?.let(paramsMap::get)?.let { paramMapping ->
                                visitDstName(MappedElementKind.METHOD_ARG, targetNamespace, paramMapping.name)

                                paramMapping.comment?.let { it ->
                                    visitComment(MappedElementKind.METHOD_ARG, it)
                                }
                            }

                            return true
                        }
                    },
                )
            }

            true
        }

        remapper.extraFileRemappers.add { mappings, fileSystem, sourceNamespace, targetNamespace ->
            val sourceNamespaceId = mappings.getNamespaceId(sourceNamespace)
            val targetNamespaceId = mappings.getNamespaceId(targetNamespace)

            for (path in fileSystem.findAccessTransformers()) {
                val mappingSet = MappingSet.create()

                for (treeClass in mappings.classes) {
                    val className = treeClass.getName(sourceNamespaceId) ?: continue
                    val mapping = mappingSet.getOrCreateClassMapping(className)

                    treeClass.getName(targetNamespaceId)?.let {
                        mapping.deobfuscatedName = it
                    }

                    for (field in treeClass.fields) {
                        val name = field.getName(sourceNamespaceId) ?: continue
                        val descriptor = field.getDesc(sourceNamespaceId)

                        val fieldMapping =
                            if (descriptor == null) {
                                mapping.getOrCreateFieldMapping(name)
                            } else {
                                mapping.getOrCreateFieldMapping(name, descriptor)
                            }

                        fieldMapping.deobfuscatedName = field.getName(targetNamespaceId)
                    }

                    for (method in treeClass.methods) {
                        val name = method.getName(sourceNamespaceId) ?: continue
                        val methodMapping = mapping.getOrCreateMethodMapping(name, method.getDesc(sourceNamespaceId))

                        method.getName(targetNamespaceId)?.let {
                            methodMapping.deobfuscatedName = it
                        }

                        for (argument in method.args) {
                            methodMapping.getOrCreateParameterMapping(
                                argument.argPosition,
                            ).deobfuscatedName = argument.getName(targetNamespaceId)
                        }
                    }
                }

                val accessTransformer = AccessTransformFormats.FML.read(path)
                path.deleteExisting()

                AccessTransformFormats.FML.write(path, accessTransformer.remap(mappingSet))
            }
        }

        remapper.remapClasspathRules.add { sourceNamespace, targetNamespace, version, mappingsConfiguration ->
            if (sourceNamespace == MinecraftCodevForgePlugin.SRG_MAPPINGS_NAMESPACE || targetNamespace == MinecraftCodevForgePlugin.SRG_MAPPINGS_NAMESPACE) {
                listOf(
                    extension<MinecraftCodevExtension>().extension<PatchedMinecraftCodevExtension>()(version)
                        .remapped(targetNamespace = sourceNamespace, mappingsConfiguration = mappingsConfiguration),
                )
            } else {
                emptyList()
            }
        }
    }
}

private fun readMcp(
    path: Path,
    source: String,
    data: MappingResolutionData,
) = path.takeIf(Path::exists)?.inputStream()?.let(data::decorate)?.reader()?.let { NamedCsvReader.builder().build(it) }?.use {
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
