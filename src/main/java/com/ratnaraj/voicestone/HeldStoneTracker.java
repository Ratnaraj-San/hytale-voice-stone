package com.ratnaraj.voicestone;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.inventory.ActiveSlotInventoryComponent;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Tracks the single Voice Stone each player effectively holds and logs
 * every held/released transition. Both hands can carry a Voice Stone at
 * the same time (hotbar = primary hand, utility slot = secondary hand),
 * but only one stone counts as held: the primary-hand stone wins,
 * mirroring the game's item-in-hand semantics.
 */
public final class HeldStoneTracker {

    private static final String UTILITY_HAND = "utility slot, secondary hand";
    private static final String HOTBAR_HAND = "hotbar, primary hand";

    private final HytaleLogger logger;
    private final Map<UUID, HeldStone> heldByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerRef> playerRefs = new ConcurrentHashMap<>();

    private record HeldStone(String stoneId, String hand) {
    }

    public HeldStoneTracker(HytaleLogger logger) {
        this.logger = logger;
    }

    /** Forgets any tracked state, so the next reading is diffed against an empty state (e.g. at connect). */
    public void reset(UUID uuid) {
        heldByPlayer.remove(uuid);
        playerRefs.remove(uuid);
    }

    public void onDisconnect(PlayerRef player) {
        HeldStone previous = heldByPlayer.remove(player.getUuid());

        playerRefs.remove(player.getUuid());

        if (previous != null) {
            logReleased(player.getUsername(), previous.stoneId(), previous.hand(), " on disconnect");
        }
    }

    public void update(PlayerRef player) {
        playerRefs.put(player.getUuid(), player);

        String hotbarStone = activeStoneId(player, InventoryComponent.Hotbar.getComponentType());
        String utilityStone = activeStoneId(player, InventoryComponent.Utility.getComponentType());

        HeldStone current;

        if (hotbarStone != null) {
            current = new HeldStone(hotbarStone, HOTBAR_HAND);
        } else if (utilityStone != null) {
            current = new HeldStone(utilityStone, UTILITY_HAND);
        } else {
            current = null;
        }

        HeldStone previous = heldByPlayer.get(player.getUuid());

        if (Objects.equals(current, previous)) {
            return;
        }

        String name = player.getUsername();
        if (previous != null) {
            logReleased(name, previous.stoneId(), previous.hand(), "");
        }
        if (current != null) {
            logger.at(Level.INFO).log("[VoiceStone] %s is now holding %s (%s)", name, current.stoneId(), current.hand());
        }
        if (current == null) {
            heldByPlayer.remove(player.getUuid());
        } else {
            heldByPlayer.put(player.getUuid(), current);
        }
    }

    private void logReleased(String player, String stone, String hand, String suffix) {
        logger.at(Level.INFO).log("[VoiceStone] %s released %s (%s%s)", player, stone, hand, suffix);
    }

    public Set<UUID> getHolders() {
        return Set.copyOf(heldByPlayer.keySet());
    }

    /**
     * Returns the Voice Stone the player currently holds, or null when
     * they hold none. Re-reads the inventory sections on every call
     * instead of serving a cached stack: link toggles replace the stack
     * wholesale, and this is also read from the voice module's
     * interceptor thread — a fresh read is always current.
     */
    public ItemStack getHeldStone(UUID uuid) {
        PlayerRef player = playerRefs.get(uuid);

        if (player == null) {
            return null;
        }

        ItemStack hotbarStone = activeStone(player, InventoryComponent.Hotbar.getComponentType());

        if (VoiceStoneItems.isVoiceStone(hotbarStone)) {
            return hotbarStone;
        }

        ItemStack utilityStone = activeStone(player, InventoryComponent.Utility.getComponentType());

        return VoiceStoneItems.isVoiceStone(utilityStone) ? utilityStone : null;
    }

    /**
     * Replaces the player's held Voice Stone with the given stack, in
     * whichever hand holds it (the same hotbar-first priority as
     * getHeldStone). withMetadata never mutates in place: the modified
     * stack must replace the old one in its slot, or the change is lost.
     *
     * @return false when no Voice Stone is held — the caller drops the
     * change rather than writing to a stale slot
     */
    public boolean writeHeldStone(PlayerRef player, ItemStack newStack) {
        ActiveSlotInventoryComponent section = heldStoneSection(player);

        if (section == null) {
            return false;
        }

        section.getInventory().setItemStackForSlot((short) section.getActiveSlot(), newStack);
        return true;
    }

    /** The section whose active item is a Voice Stone, hotbar (primary hand) first. */
    private ActiveSlotInventoryComponent heldStoneSection(PlayerRef player) {
        InventoryComponent.Hotbar hotbar =
                player.getComponentConcurrent(InventoryComponent.Hotbar.getComponentType());

        if (VoiceStoneItems.isVoiceStone(hotbar.getActiveItem())) {
            return hotbar;
        }

        InventoryComponent.Utility utility =
                player.getComponentConcurrent(InventoryComponent.Utility.getComponentType());

        return VoiceStoneItems.isVoiceStone(utility.getActiveItem()) ? utility : null;
    }

    private <T extends ActiveSlotInventoryComponent> ItemStack activeStone(
            PlayerRef player, ComponentType<EntityStore, T> sectionType) {
        T section = player.getComponentConcurrent(sectionType);
        if (section == null) {
            return null;
        }
        return section.getActiveItem();
    }

    private <T extends ActiveSlotInventoryComponent> String activeStoneId(
            PlayerRef player, ComponentType<EntityStore, T> sectionType) {
        ItemStack activeItem = activeStone(player, sectionType);

        return VoiceStoneItems.isVoiceStone(activeItem) ? activeItem.getItemId() : null;
    }
}
