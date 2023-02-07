package net.msrandom.minecraftcodev.core.dependency

import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import java.net.URL

abstract class LaunchWrapperTransformer : AsmJarTransformer("net/minecraft/launchwrapper/Launch.class") {
    override fun editNode(node: ClassNode) {
        val init = node.methods.first { it.name == "<init>" }

        val iterator = init.instructions.iterator()

        init.localVariables.removeAt(1)

        while (iterator.hasNext()) {
            val instruction = iterator.next()
            if (instruction.opcode == Opcodes.INVOKEVIRTUAL) {
                iterator.previous()

                while (iterator.next().opcode != Opcodes.NEW) {
                    iterator.remove()
                }

                iterator.next()
                iterator.next() // ALOAD
                iterator.remove()
                iterator.next() // INVOKEVIRTUAL URLClassLoader.getURLs ()[LURL;
                iterator.remove()
                iterator.add(MethodInsnNode(Opcodes.INVOKESTATIC, "cpw/mods/gross/Java9ClassLoaderUtil", "getSystemClassPathURLs", Type.getMethodDescriptor(Type.getType('[' + Type.getDescriptor(URL::class.java)))))

                break
            }
        }
    }
}
