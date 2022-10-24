import net.minecraft.server.MinecraftServer;
import net.minecraftforge.forge.MinecraftForge;

public class Test {
    public static void test() {
        System.out.println(MinecraftForge.EVENT_BUS);
        MinecraftServer.main(new String[0]);
    }
}
