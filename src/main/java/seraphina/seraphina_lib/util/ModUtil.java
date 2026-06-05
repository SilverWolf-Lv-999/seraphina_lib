package seraphina.seraphina_lib.util;

import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLEnvironment;

import java.util.HashSet;
import java.util.Set;

/**
 * Utility methods for querying Forge mod metadata and launch environment state.
 */
public class ModUtil {
    /**
     * Loads every class discovered by Forge's mod scan data.
     * <p>
     * Classes that cannot be loaded with the current context or library class loader
     * are skipped.
     *
     * @return all loadable classes found in scanned mod metadata
     */
    public static Set<Class<?>> getAllModeClass() {
        Set<Class<?>> set = new HashSet<>();
        ModList.get().getAllScanData().forEach(scanData -> {
            scanData.getClasses().forEach(clazz -> {
                Class<?> loadedClass = loadClass(clazz.clazz().getClassName());
                if (loadedClass != null) {
                    set.add(loadedClass);
                }
            });
        });
        return set;
    }

    private static Class<?> loadClass(String className) {
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        if (contextClassLoader != null) {
            try {
                return Class.forName(className, false, contextClassLoader);
            } catch (Throwable ignored) {
            }
        }

        try {
            return Class.forName(className, false, ModUtil.class.getClassLoader());
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Checks whether Forge has loaded the mod with the supplied id.
     *
     * @param modid mod id to check
     * @return {@code true} if the mod is present in the loaded mod list
     */
    public static boolean isModLoaded(String modid) {
        return ModList.get().isLoaded(modid);
    }

    /**
     * Returns whether the current Forge launch is a development environment.
     *
     * @return {@code true} when Forge is not running in production mode
     */
    public static boolean isDevelopment() {
        return !FMLEnvironment.production;
    }
}
