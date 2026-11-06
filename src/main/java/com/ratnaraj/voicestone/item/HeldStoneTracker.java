package com.ratnaraj.voicestone.item;

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
 * every held/released transition. The held stone is the Voice Stone
 * selected in the hotbar (primary hand).
 */
public final class HeldStoneTracker {

    private final HytaleLogger logger;
    private final Map<UUID, String> heldByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerRef> playerRefs = new ConcurrentHashMap<>();

    public HeldStoneTracker(HytaleLogger logger) {
        this.logger = logger;
    }

    /** Forgets any tracked state, so the next reading is diffed against an empty state (e.g. at connect). */
    public void reset(UUID uuid) {
        heldByPlayer.remove(uuid);
        playerRefs.remove(uuid);
    }

    public void onDisconnect(PlayerRef player) {
        String previous = heldByPlayer.remove(player.getUuid());

        playerRefs.remove(player.getUuid());

        if (previous != null) {
            logReleased(player.getUsername(), previous, " on disconnect");
        }
    }

    public void update(PlayerRef player) {
        playerRefs.put(player.getUuid(), player);

        String current = activeStoneId(player, InventoryComponent.Hotbar.getComponentType());

        String previous = heldByPlayer.get(player.getUuid());

        if (Objects.equals(current, previous)) {
            return;
        }

        String name = player.getUsername();
        if (previous != null) {
            logReleased(name, previous, "");
        }
        if (current != null) {
            logger.at(Level.INFO).log("[VoiceStone] %s is now holding %s", name, current);
        }
        if (current == null) {
            heldByPlayer.remove(player.getUuid());
        } else {
            heldByPlayer.put(player.getUuid(), current);
        }
    }

    private void logReleased(String player, String stone, String suffix) {
        logger.at(Level.INFO).log("[VoiceStone] %s released %s%s", player, stone, suffix);
    }

    public Set<UUID> getHolders() {
        return Set.copyOf(heldByPlayer.keySet());
    }

    /**
     * Returns the Voice Stone the player currently holds, or null when
     * they hold none. Re-reads the inventory section on every call
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

        return VoiceStoneItems.isVoiceStone(hotbarStone) ? hotbarStone : null;
    }

    /**
     * Replaces the player's held Voice Stone with the given stack, in
     * the hotbar slot holding it. withMetadata never mutates in place:
     * the modified stack must replace the old one in its slot, or the
     * change is lost.
     *
     * @return false when no Voice Stone is held — the caller drops the
     * change rather than writing to a stale slot
     */
    public boolean writeHeldStone(PlayerRef player, ItemStack newStack) {
        InventoryComponent.Hotbar hotbar =
                player.getComponentConcurrent(InventoryComponent.Hotbar.getComponentType());

        if (hotbar == null || !VoiceStoneItems.isVoiceStone(hotbar.getActiveItem())) {
            return false;
        }

        hotbar.getInventory().setItemStackForSlot((short) hotbar.getActiveSlot(), newStack);
        return true;
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
