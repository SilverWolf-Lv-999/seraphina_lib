package seraphina.seraphina_lib.forge;

import com.mojang.brigadier.CommandDispatcher;
import lombok.Getter;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraftforge.eventbus.api.Event;

@Getter
public class CommandInitEvent extends Event {
    final CommandDispatcher<CommandSourceStack> dispatcher;

    final Commands.CommandSelection selection;

    final CommandBuildContext context;

    public CommandInitEvent(CommandDispatcher<CommandSourceStack> dispatcher, Commands.CommandSelection selection, CommandBuildContext context) {
        this.dispatcher = dispatcher;
        this.selection = selection;
        this.context = context;
    }
}
