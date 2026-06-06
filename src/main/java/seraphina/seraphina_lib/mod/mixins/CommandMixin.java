package seraphina.seraphina_lib.mod.mixins;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import seraphina.seraphina_lib.mixin.annotation.Inject;
import seraphina.seraphina_lib.mixin.annotation.SeraMixin;
import seraphina.seraphina_lib.mixin.annotation.Shadow;
import seraphina.seraphina_lib.mixin.util.InsertPosition;
import seraphina.seraphina_lib.util.entity.EntityDataInject;

@SeraMixin(Commands.class)
public class CommandMixin {
    @Shadow
    private CommandDispatcher<CommandSourceStack> dispatcher;

    @Inject(methodName = "<init>", desc = "(Lnet/minecraft/commands/Commands$CommandSelection;Lnet/minecraft/commands/CommandBuildContext;)V", at = InsertPosition.LAST)
    public void onInit(Commands.CommandSelection pSelection, CommandBuildContext pContext) {
        dispatcher.register(LiteralArgumentBuilder.<CommandSourceStack>literal("seraphina_lib")
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("entity")
                        .then(LiteralArgumentBuilder.<CommandSourceStack>literal("setDefEntity")
                                .then(RequiredArgumentBuilder.<CommandSourceStack, EntitySelector>argument("target", EntityArgument.entities())
                                        .then(RequiredArgumentBuilder.<CommandSourceStack, Boolean>argument("bool", BoolArgumentType.bool())
                                                .executes(context -> {
                                                    Entity entity = EntityArgument.getEntity(context, "target");
                                                    if (entity instanceof LivingEntity livingEntity) {
                                                        EntityDataInject.setDef(livingEntity, BoolArgumentType.getBool(context, "bool"));
                                                    }
                                                    return 4;
                                                })
                                        )
                                )
                        )
                )
        );
    }
}
