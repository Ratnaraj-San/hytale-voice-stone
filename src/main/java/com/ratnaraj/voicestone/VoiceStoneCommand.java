package com.ratnaraj.voicestone;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/**
 * The explicit link commands: {@code /voicestone add <player>},
 * {@code /voicestone remove <player>}, {@code /voicestone clear} and
 * {@code /voicestone list} operate on the Voice Stone currently held by
 * the sender. They are the precise, no-guesswork counterpart to the two
 * physical gestures — crouch + left-click ({@link LinkGestureSystem},
 * needs PvP) and crouch + right-click ({@link LinkChainSystem}, works
 * everywhere). Links belong to the stone, never to its holder.
 */
public final class VoiceStoneCommand extends AbstractCommandCollection {

    public VoiceStoneCommand(HytaleLogger logger, HeldStoneTracker tracker) {
        super("voicestone", "commands.voicestone.desc");
        addSubCommand(new AddCommand(logger, tracker));
        addSubCommand(new RemoveCommand(logger, tracker));
        addSubCommand(new ClearCommand(logger, tracker));
        addSubCommand(new ListCommand(tracker));
    }

    /**
     * A /voicestone subcommand operating on the sender's held stone: the
     * sender must be a player holding a Voice Stone, and cannot target
     * themselves. Subclasses receive the resolved sender and stone.
     */
    private abstract static class StoneSubCommand extends CommandBase {

        final HytaleLogger logger;
        final HeldStoneTracker tracker;

        StoneSubCommand(String name, String descriptionKey, HytaleLogger logger, HeldStoneTracker tracker) {
            super(name, descriptionKey);
            this.logger = logger;
            this.tracker = tracker;
            requireNoPermission();
        }

        @Override
        protected final void executeSync(CommandContext context) {
            if (!context.isPlayer()) {
                return;
            }

            PlayerRef sender = context.senderAs(PlayerRef.class);

            if (sender == null) {
                return;
            }

            ItemStack stone = tracker.getHeldStone(sender.getUuid());

            if (stone == null) {
                context.sendMessage(Message.raw("Hold a Voice Stone first."));
                return;
            }

            execute(context, sender, stone);
        }

        protected abstract void execute(CommandContext context, PlayerRef sender, ItemStack stone);

        /**
         * Writes the new stack back into the held stone. Returns false
         * (with feedback sent) when the stone slipped between read and
         * write — the caller must drop the change.
         */
        final boolean applyLinks(CommandContext context, PlayerRef sender, ItemStack newStack) {
            if (tracker.writeHeldStone(sender, newStack)) {
                return true;
            }

            context.sendMessage(Message.raw("The stone slipped out of your hands, nothing changed."));
            return false;
        }

        final void logChange(PlayerRef sender, PlayerRef target, String action, ItemStack stone, int remaining) {
            logger.at(Level.INFO).log(
                    "[VoiceStone] %s -> %s : %s on %s (%d links remaining)",
                    sender.getUsername(),
                    target.getUsername(),
                    action,
                    stone.getItemId(),
                    remaining
            );
        }
    }

    private static final class AddCommand extends StoneSubCommand {

        private final RequiredArg<PlayerRef> playerArg;

        AddCommand(HytaleLogger logger, HeldStoneTracker tracker) {
            super("add", "commands.voicestone.add.desc", logger, tracker);
            this.playerArg = withRequiredArg("player", "commands.voicestone.player.desc", ArgTypes.PLAYER_REF);
        }

        @Override
        protected void execute(CommandContext context, PlayerRef sender, ItemStack stone) {
            PlayerRef target = playerArg.get(context);

            if (target == null || target.getUuid().equals(sender.getUuid())) {
                context.sendMessage(Message.raw(" You cannot link a stone to yourself."));
                return;
            }

            if (StoneLinks.get(stone).contains(target.getUuid())) {
                context.sendMessage(Message.raw(target.getUsername() + " is already linked to this stone."));
                return;
            }

            ItemStack linked = StoneLinks.link(stone, target.getUuid(), target.getUsername());

            if (!applyLinks(context, sender, linked)) {
                return;
            }

            int remaining = StoneLinks.get(linked).size();

            logChange(sender, target, "link", stone, remaining);
            context.sendMessage(Message.raw(
                    target.getUsername() + " is now linked to this stone."));
        }
    }

    private static final class RemoveCommand extends StoneSubCommand {

        private final RequiredArg<PlayerRef> playerArg;

        RemoveCommand(HytaleLogger logger, HeldStoneTracker tracker) {
            super("remove", "commands.voicestone.remove.desc", logger, tracker);
            this.playerArg = withRequiredArg("player", "commands.voicestone.player.desc", ArgTypes.PLAYER_REF);
        }

        @Override
        protected void execute(CommandContext context, PlayerRef sender, ItemStack stone) {
            PlayerRef target = playerArg.get(context);

            if (target == null) {
                return;
            }

            if (!StoneLinks.get(stone).contains(target.getUuid())) {
                context.sendMessage(Message.raw(target.getUsername() + " is not linked to this stone."));
                return;
            }

            ItemStack linked = StoneLinks.unlink(stone, target.getUuid());

            if (!applyLinks(context, sender, linked)) {
                return;
            }

            int remaining = StoneLinks.get(linked).size();

            logChange(sender, target, "unlink", stone, remaining);
            context.sendMessage(Message.raw(
                    target.getUsername() + "is no longer linked to this stone)"));
        }
    }

    private static final class ClearCommand extends StoneSubCommand {

        ClearCommand(HytaleLogger logger, HeldStoneTracker tracker) {
            super("clear", "commands.voicestone.clear.desc", logger, tracker);
        }

        @Override
        protected void execute(CommandContext context, PlayerRef sender, ItemStack stone) {
            List<UUID> links = StoneLinks.get(stone);

            if (links.isEmpty()) {
                context.sendMessage(Message.raw("This stone has no links."));
                return;
            }

            int removed = links.size();

            if (!applyLinks(context, sender, StoneLinks.clear(stone))) {
                return;
            }

            logger.at(Level.INFO).log(
                    "[VoiceStone] %s cleared %s (%d links removed)",
                    sender.getUsername(),
                    stone.getItemId(),
                    removed
            );

            context.sendMessage(Message.raw(
                    removed + (removed == 1 ? " link" : " links") + " removed from this stone."));
        }
    }

    private static final class ListCommand extends StoneSubCommand {

        ListCommand(HeldStoneTracker tracker) {
            super("list", "commands.voicestone.list.desc", null, tracker);
        }

        @Override
        protected void execute(CommandContext context, PlayerRef sender, ItemStack stone) {
            List<UUID> links = StoneLinks.get(stone);

            if (links.isEmpty()) {
                context.sendMessage(Message.raw("This stone has no links."));
                return;
            }

            List<String> names = new ArrayList<>(links.size());
            for (UUID link : links) {
                PlayerRef player = Universe.get().getPlayer(link);

                if (player != null) {
                    names.add(player.getUsername());
                    continue;
                }

                String recorded = StoneLinks.getName(stone, link);
                names.add(recorded == null ? "?" : recorded);
            }

            context.sendMessage(Message.raw(
                    "This stone is linked to " + names.size() + (names.size() <= 1 ? " player" : " players") + String.join(", ", names)));
        }
    }
}
