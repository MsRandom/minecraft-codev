package net.msrandom.minecraftcodev.forge

import net.msrandom.minecraftcodev.core.dependency.AsmJarTransformer
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.*

abstract class ForgeJarTransformer : AsmJarTransformer("net/minecraftforge/fml/loading/targets/ForgeClientUserdevLaunchHandler.class") {
    private fun MethodNode.loadPaths(property: String, index: Int) {
        // ArrayList var1 = new ArrayList();
        instructions.add(TypeInsnNode(Opcodes.NEW, "java/util/ArrayList"))
        instructions.add(InsnNode(Opcodes.DUP))
        instructions.add(MethodInsnNode(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V"))
        instructions.add(VarInsnNode(Opcodes.ASTORE, index))

        // String[] var2 = System.getProperty("minecraftCodev.minecraftJars", "").split(",")
        instructions.add(LdcInsnNode(property))
        instructions.add(LdcInsnNode(""))
        instructions.add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/System", "getProperty", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"))
        instructions.add(LdcInsnNode(","))
        instructions.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "split", "(Ljava/lang/String;)[Ljava/lang/String;"))
        instructions.add(VarInsnNode(Opcodes.ASTORE, index + 1))

        // int var3 = var2.length;
        instructions.add(VarInsnNode(Opcodes.ALOAD, index + 1))
        instructions.add(InsnNode(Opcodes.ARRAYLENGTH))
        instructions.add(VarInsnNode(Opcodes.ISTORE, index + 2))

        // int var4 = 0;
        instructions.add(InsnNode(Opcodes.ICONST_0))
        instructions.add(VarInsnNode(Opcodes.ISTORE, index + 3))

        val minecraftLoopStart = LabelNode()
        val minecraftLoopEnd = LabelNode()

        // minecraftLoopStart:
        instructions.add(minecraftLoopStart)

        // if (var4 >= var3) goto minecraftLoopEnd;
        instructions.add(VarInsnNode(Opcodes.ILOAD, index + 3))
        instructions.add(VarInsnNode(Opcodes.ILOAD, index + 2))
        instructions.add(JumpInsnNode(Opcodes.IF_ICMPGE, minecraftLoopEnd))

        // var1.add(Paths.get(var2[var4]))
        instructions.add(VarInsnNode(Opcodes.ALOAD, index))
        instructions.add(VarInsnNode(Opcodes.ALOAD, index + 1))
        instructions.add(VarInsnNode(Opcodes.ILOAD, index + 3))
        instructions.add(InsnNode(Opcodes.AALOAD))
        instructions.add(InsnNode(Opcodes.ICONST_0))
        instructions.add(TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/String"))
        instructions.add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/nio/file/Paths", "get", "(Ljava/lang/String;[Ljava/lang/String;)Ljava/nio/file/Path;"))
        instructions.add(MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z"))
        instructions.add(InsnNode(Opcodes.POP))

        // ++var4
        instructions.add(IincInsnNode(index + 3, 1))

        // goto minecraftLoopStart;
        instructions.add(JumpInsnNode(Opcodes.GOTO, minecraftLoopStart))

        // minecraftLoopEnd:
        instructions.add(minecraftLoopEnd)
    }

    override fun editNode(node: ClassNode) {
        val methodIndex = node.methods.indexOfFirst { it.name == "getMinecraftPaths" }

        node.methods[methodIndex] = MethodNode().apply {
            val old = node.methods[methodIndex]
            name = old.name
            desc = old.desc

            loadPaths("minecraftCodev.minecraftJars", 1)
            loadPaths("minecraftCodev.fmlJars", 5)

            val index = 9

            instructions.add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/stream/Stream", "builder", "()Ljava/util/stream/Stream\$Builder;", true))
            instructions.add(VarInsnNode(Opcodes.ASTORE, index))

            // Iterator var6 = getModClasses().entrySet().iterator()
            instructions.add(VarInsnNode(Opcodes.ALOAD, 0))
            instructions.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraftforge/fml/loading/targets/CommonLaunchHandler", "getModClasses", "()Ljava/util/Map;"))
            instructions.add(MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map", "entrySet", "()Ljava/util/Set;"))
            instructions.add(MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/lang/Iterable", "iterator", "()Ljava/util/Iterator;"))
            instructions.add(VarInsnNode(Opcodes.ASTORE, index + 1))

            val modLoopStart = LabelNode()
            val modLoopEnd = LabelNode()

            // modLoopStart:
            instructions.add(modLoopStart)

            // if (!var6.hasNext()) goto modLoopEnd;
            instructions.add(VarInsnNode(Opcodes.ALOAD, index + 1))
            instructions.add(MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z"))
            instructions.add(JumpInsnNode(Opcodes.IFEQ, modLoopEnd))

            // var5.add(var6.next().getValue());
            instructions.add(VarInsnNode(Opcodes.ALOAD, index))
            instructions.add(VarInsnNode(Opcodes.ALOAD, index + 1))
            instructions.add(MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;"))
            instructions.add(MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map\$Entry", "getValue", "()Ljava/lang/Object;"))
            instructions.add(MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/stream/Stream\$Builder", "add", "(Ljava/lang/Object;)Ljava/util/stream/Stream\$Builder;"))
            instructions.add(InsnNode(Opcodes.POP))

            // goto modLoopStart;
            instructions.add(JumpInsnNode(Opcodes.GOTO, modLoopStart))

            // modLoopEnd:
            instructions.add(modLoopEnd)

            // return new LocatedPaths();
            instructions.add(TypeInsnNode(Opcodes.NEW, "net/minecraftforge/fml/loading/targets/CommonLaunchHandler\$LocatedPaths"))
            instructions.add(InsnNode(Opcodes.DUP))

            instructions.add(VarInsnNode(Opcodes.ALOAD, 1))

            // this.getMcFilter(Paths.get(...), var1, var9)
            instructions.add(VarInsnNode(Opcodes.ALOAD, 0))
            instructions.add(LdcInsnNode("/fakepath/b538a141-5c9a-4e55-8db2-38242acf0d73/shouldnotexist/"))
            instructions.add(InsnNode(Opcodes.ICONST_0))
            instructions.add(TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/String"))
            instructions.add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/nio/file/Paths", "get", "(Ljava/lang/String;[Ljava/lang/String;)Ljava/nio/file/Path;"))
            instructions.add(VarInsnNode(Opcodes.ALOAD, 1))
            instructions.add(VarInsnNode(Opcodes.ALOAD, index))
            instructions.add(
                MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    "net/minecraftforge/fml/loading/targets/CommonDevLaunchHandler",
                    "getMcFilter",
                    "(Ljava/nio/file/Path;Ljava/util/List;Ljava/util/stream/Stream\$Builder;)Ljava/util/function/BiPredicate;"
                )
            )

            instructions.add(VarInsnNode(Opcodes.ALOAD, index))
            instructions.add(MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/stream/Stream\$Builder", "build", "()Ljava/util/stream/Stream;"))
            instructions.add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/stream/Collectors", "toList", "()Ljava/util/stream/Collector;"))
            instructions.add(MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/stream/Stream", "collect", "(Ljava/util/stream/Collector;)Ljava/lang/Object;"))

            instructions.add(VarInsnNode(Opcodes.ALOAD, 5))

            instructions.add(
                MethodInsnNode(
                    Opcodes.INVOKESPECIAL,
                    "net/minecraftforge/fml/loading/targets/CommonLaunchHandler\$LocatedPaths",
                    "<init>",
                    "(Ljava/util/List;Ljava/util/function/BiPredicate;Ljava/util/List;Ljava/util/List;)V"
                )
            )

            instructions.add(InsnNode(Opcodes.ARETURN))
        }
    }
}
