package seraphina.seraphina_lib;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import seraphina.seraphina_lib.forge.CommandInitEvent;
import seraphina.seraphina_lib.logger.Logger;
import seraphina.seraphina_lib.logger.LoggerFactory;
import seraphina.seraphina_lib.mixin.service.SeraMixinTransformationService;
import seraphina.seraphina_mod.common.CommandUtil;
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

    public static final String MOD_ID = "seraphina_lib";
    private static final Logger LOGGER = LoggerFactory.getLogger(LIBSource.class);

    public LIBSource(FMLJavaModLoadingContext context) {
        MinecraftForge.EVENT_BUS.addListener(this::command);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ClientOnly.register(context);
        }
        LOGGER.info("Seraphina Lib Initialized");
        if (ModUtil.isDevelopment()) {
            LOGGER.info("Development mode enabled");
            LOGGER.debug("Find {} count mods", ModList.get().size());
             ModList.get().getMods().forEach(mod -> {
                LOGGER.debug(" -{}", mod.getModId());
            });
        }
    }

    public void command(CommandInitEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        CommandUtil.registerCommands(dispatcher);
    }

    static {
        ModuleUtil.INSTANCE.openAllRequiredModules(LIBSource.class);
        ModuleUtil.INSTANCE.openCurrentModuleToAllRequiredModules(LIBSource.class);
        SeraMixinTransformationService.bootstrapFromModEntry();
    }

    private static final class ClientOnly {
        private static void register(FMLJavaModLoadingContext context) {
            context.getModEventBus().addListener(seraphina.seraphina_mod.client.particle.ParticleUtil::registerShaders);
        }
    }
}
