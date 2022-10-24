package net.msrandom.minecraftcodev.forge

import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.*

object ClientJarAppender {
    fun appendTo(node: ClassNode) {
        node.methods.add(
            MethodNode().apply {
                val parent = "net/minecraftforge/fml/loading/targets/ForgeUserdevLaunchHandler"

                // protected void processStreams(String[] var1, VersionInfo var2, Stream.Builder var3, Stream.Builder var4)
                access = Opcodes.ACC_PROTECTED
                name = "processStreams"
                desc = "([Ljava/lang/String;Lnet/minecraftforge/fml/loading/VersionInfo;Ljava/util/stream/Stream\$Builder;Ljava/util/stream/Stream\$Builder;)V"

                // super.processStreams(var1, var2, var3, var4)
                instructions.add(VarInsnNode(Opcodes.ALOAD, 0))
                instructions.add(VarInsnNode(Opcodes.ALOAD, 1))
                instructions.add(VarInsnNode(Opcodes.ALOAD, 2))
                instructions.add(VarInsnNode(Opcodes.ALOAD, 3))
                instructions.add(VarInsnNode(Opcodes.ALOAD, 4))
                instructions.add(MethodInsnNode(Opcodes.INVOKESPECIAL, parent, name, desc))

                // var var5 = new StringBuilder("forge-client-")
                instructions.add(TypeInsnNode(Opcodes.NEW, "java/lang/StringBuilder"))
                instructions.add(InsnNode(Opcodes.DUP))
                instructions.add(LdcInsnNode("forge-client-"))
                instructions.add(MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V"))
                instructions.add(VarInsnNode(Opcodes.ASTORE, 5))

                // var5.append(var2.mcVersion())
                instructions.add(VarInsnNode(Opcodes.ALOAD, 5))
                instructions.add(VarInsnNode(Opcodes.ALOAD, 2))
                instructions.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraftforge/fml/loading/VersionInfo", "mcVersion", "()Ljava/lang/String;"))
                instructions.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;"))

                // var3.add(ForgeUserdevLaunchHandler.findJarOnClasspath(var5.toString()))
                instructions.add(VarInsnNode(Opcodes.ALOAD, 3))
                instructions.add(VarInsnNode(Opcodes.ALOAD, 1))
                instructions.add(VarInsnNode(Opcodes.ALOAD, 5))
                instructions.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;"))
                instructions.add(MethodInsnNode(Opcodes.INVOKESTATIC, parent, "findJarOnClasspath", "([Ljava/lang/String;Ljava/lang/String;)Ljava/nio/file/Path;"))
                instructions.add(MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/stream/Stream\$Builder", "add", "(Ljava/lang/Object;)Ljava/util/stream/Stream\$Builder;"))

                instructions.add(InsnNode(Opcodes.RETURN))
            }
        )
    }
}
