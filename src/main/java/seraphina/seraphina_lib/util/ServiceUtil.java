package seraphina.seraphina_lib.util;

import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Path;

/**
 * Utility entry points for service-layer bootstrap work.
 */
public class ServiceUtil {
    /**
     * Root game directory reported by Forge.
     */
    static final Path MOD_DIR = FMLPaths.GAMEDIR.get();

    /**
     * Loads mixin service state.
     * <p>
     * This method is currently a reserved bootstrap hook.
     */
    public static void loadMixin() {}
}
