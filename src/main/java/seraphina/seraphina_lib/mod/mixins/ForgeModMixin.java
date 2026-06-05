package seraphina.seraphina_lib.mod.mixins;

import net.minecraftforge.common.ForgeMod;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import seraphina.seraphina_lib.forge.ForgeModInitEvent;
import seraphina.seraphina_lib.mixin.annotation.Inject;
import seraphina.seraphina_lib.mixin.annotation.SeraMixin;
import seraphina.seraphina_lib.mixin.util.InsertPosition;

@SeraMixin(ForgeMod.class)
public class ForgeModMixin {
    @Inject(methodName = "<init>", desc = "(Lnet/minecraftforge/fml/javafmlmod/FMLJavaModLoadingContext;)V", at = InsertPosition.HEAD)
    public void preInit(FMLJavaModLoadingContext fmlJavaModLoadingContext) {
        ForgeMod forgeMod = (ForgeMod) (Object)this;
        MinecraftForge.EVENT_BUS.post(new ForgeModInitEvent.Pre(forgeMod));
    }

    @Inject(methodName = "<init>", desc = "(Lnet/minecraftforge/fml/javafmlmod/FMLJavaModLoadingContext;)V", at = InsertPosition.LAST)
    public void postInit(FMLJavaModLoadingContext fmlJavaModLoadingContext) {
        ForgeMod forgeMod = (ForgeMod) (Object)this;
        MinecraftForge.EVENT_BUS.post(new ForgeModInitEvent.Post(forgeMod));
    }
}
