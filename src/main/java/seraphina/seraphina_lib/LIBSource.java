package seraphina.seraphina_lib;

import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import seraphina.seraphina_lib.logger.Logger;
import seraphina.seraphina_lib.logger.LoggerFactory;
import seraphina.seraphina_lib.util.ModUtil;
import seraphina.seraphina_lib.util.ModuleUtil;

/**
 * Forge mod entry point for SeraphinaLib.
 * <p>
 * Loading this class also opens the internal JDK and runtime modules needed by
 * the reflection, mixin, and rendering helpers in the library.
 */
@Mod(LIBSource.MOD_ID)
public class LIBSource {
    /**
     * Forge mod id used by the loader and resource system.
     */
    public static final String MOD_ID = "seraphina_lib";
    private static final Logger LOGGER = LoggerFactory.getLogger(LIBSource.class);

    /**
     * Creates the library mod entry point.
     */
    public LIBSource() {
        LOGGER.info("Seraphina Lib Initialized");
        if (ModUtil.isDevelopment()) {
            LOGGER.info("Development mode enabled");
            LOGGER.debug("Find {} count mods", ModList.get().size());
            ModList.get().getMods().forEach(mod -> {
                LOGGER.debug(" -{}", mod.getModId());
            });
        }
    }

    static {
        ModuleUtil.INSTANCE.openAllRequiredModules(LIBSource.class);
        ModuleUtil.INSTANCE.openCurrentModuleToAllRequiredModules(LIBSource.class);
    }
}
