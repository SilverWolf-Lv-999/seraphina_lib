package seraphina.seraphina_lib.util;

import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLEnvironment;

import java.util.HashSet;
import java.util.Set;

public class ModUtil {
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

    public static boolean isModLoaded(String modid) {
        return ModList.get().isLoaded(modid);
    }

    public static boolean isDevelopment() {
        return !FMLEnvironment.production;
    }
}
