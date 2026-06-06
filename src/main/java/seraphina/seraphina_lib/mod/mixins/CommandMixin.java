package seraphina.seraphina_lib.mod.mixins;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraftforge.common.MinecraftForge;
import seraphina.seraphina_lib.forge.CommandInitEvent;
import seraphina.seraphina_lib.mixin.annotation.Inject;
import seraphina.seraphina_lib.mixin.annotation.SeraMixin;
import seraphina.seraphina_lib.mixin.annotation.Shadow;
import seraphina.seraphina_lib.mixin.util.InsertPosition;

@SeraMixin(Commands.class)
public class CommandMixin {
    @Shadow
    private CommandDispatcher<CommandSourceStack> dispatcher;

    @Inject(methodName = "<init>", desc = "(Lnet/minecraft/commands/Commands$CommandSelection;Lnet/minecraft/commands/CommandBuildContext;)V", at = InsertPosition.LAST)
    public void onInit(Commands.CommandSelection pSelection, CommandBuildContext pContext) {
        MinecraftForge.EVENT_BUS.post(new CommandInitEvent(dispatcher, pSelection, pContext));
    }
}
