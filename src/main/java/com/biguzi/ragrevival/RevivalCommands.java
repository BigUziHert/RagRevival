package com.biguzi.ragrevival;

import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Administrative revival uses the same cleanup and protection as item-based revival. */
public final class RevivalCommands {
    private RevivalCommands() {}

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("revive")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("player", EntityArgument.player()).executes(context -> {
                    ServerPlayer player = EntityArgument.getPlayer(context, "player");
                    if (!player.isAlive()) {
                        context.getSource().sendFailure(Component.translatable("commands.ragrevival.revive.not_alive", player.getDisplayName()));
                        return 0;
                    }
                    if (!DownedManager.isDowned(player)) {
                        context.getSource().sendFailure(Component.translatable("commands.ragrevival.revive.not_downed", player.getDisplayName()));
                        return 0;
                    }
                    DownedManager.revive(player);
                    context.getSource().sendSuccess(() -> Component.translatable("commands.ragrevival.revive.success", player.getDisplayName()), true);
                    return 1;
                })));
    }
}
