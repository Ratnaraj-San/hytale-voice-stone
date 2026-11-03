package com.ratnaraj.voicestone;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import java.util.logging.Level;

/**
 * The link gesture for servers without PvP: {@code /voicestone <player>}
 * toggles the link of the stone currently held by the sender. The
 * physical gesture (crouch + left-click, see {@link LinkGestureSystem})
 * only works where PvP is enabled — the attack pipeline is gated
 * upstream of the Damage event — so on a PvE server the command is the
 * way to link. Same semantics otherwise: the toggle lives on the stone,
 * several players can be linked, the sender must hold a Voice Stone.
 */
public final class LinkCommand extends CommandBase {

    private final HytaleLogger logger;
    private final HeldStoneTracker tracker;

    private final RequiredArg<PlayerRef> playerArg;

    public LinkCommand(HytaleLogger logger, HeldStoneTracker tracker) {
        super("voicestone", "commands.voicestone.desc");
        this.logger = logger;
        this.tracker = tracker;
        this.playerArg = withRequiredArg("player", "commands.voicestone.player.desc", ArgTypes.PLAYER_REF);
        requireNoPermission();
    }

    @Override
    protected void executeSync(CommandContext context) {
        if (!context.isPlayer()) {
            return;
        }

        PlayerRef sender = context.senderAs(PlayerRef.class);
        PlayerRef target = playerArg.get(context);

        if (sender == null || target == null || target.getUuid().equals(sender.getUuid())) {
            context.sendMessage(Message.raw("[VoiceStone] You cannot link a stone to yourself."));
            return;
        }

        ItemStack stone = tracker.getHeldStone(sender.getUuid());

        if (stone == null) {
            context.sendMessage(Message.raw("[VoiceStone] Hold a Voice Stone to link it."));
            return;
        }

        boolean wasLinked = StoneLinks.get(stone).contains(target.getUuid());

        ItemStack linked = StoneLinks.toggle(stone, target.getUuid());

        if (!tracker.writeHeldStone(sender, linked)) {
            context.sendMessage(Message.raw("[VoiceStone] The stone slipped out of your hands, nothing was linked."));
            return;
        }

        int remaining = StoneLinks.get(linked).size();

        logger.at(Level.INFO).log(
                "[VoiceStone] %s -> %s : %s on %s (%d links remaining)",
                sender.getUsername(),
                target.getUsername(),
                wasLinked ? "unlink" : "link",
                stone.getItemId(),
                remaining
        );

        context.sendMessage(Message.raw(
                "[VoiceStone] " + target.getUsername()
                        + (wasLinked ? " unlinked" : " linked")
                        + " (" + remaining + " links on this stone)"
        ));
    }
}
