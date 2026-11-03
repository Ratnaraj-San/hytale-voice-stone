package com.ratnaraj.voicestone;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.inventory.ActiveSlotInventoryComponent;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Tracks the single Voice Stone each player effectively holds and logs every
 * held/released transition. Both hands can carry a Voice Stone at the same
 * time (hotbar = primary hand, utility slot = secondary hand), but only one
 * stone counts as held: the primary-hand stone wins over the secondary-hand
 * one, mirroring the game's own item-in-hand semantics.
 */
public final class HeldStoneTracker {

    private static final String UTILITY_HAND = "utility slot, secondary hand";
    private static final String HOTBAR_HAND = "hotbar, primary hand";

    private final HytaleLogger logger;
    private final Map<UUID, HeldStone> heldByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerRef> playerRefs = new ConcurrentHashMap<>();
    private final List<Consumer<UUID>> stoneReleasedCallbacks = new CopyOnWriteArrayList<>();

    private record HeldStone(String stoneId, String hand) {
    }

    public HeldStoneTracker(HytaleLogger logger) {
        this.logger = logger;
    }

    /**
     * Forgets any tracked state for a player without logging, so a fresh
     * reading (e.g. at connect) is diffed against an empty state.
     */
    public void reset(UUID uuid) {
        heldByPlayer.remove(uuid);
        playerRefs.remove(uuid);
    }

    /** Clears a player's state on disconnect, logging any stone they were holding. */
    public void onDisconnect(PlayerRef player) {
        HeldStone previous = heldByPlayer.remove(player.getUuid());

        playerRefs.remove(player.getUuid());

        if (previous != null) {
            logReleased(player.getUsername(), previous.stoneId(), previous.hand(), " on disconnect");
            notifyStoneReleased(player.getUuid());
        }
    }

    /** Recomputes the held stone of a player. */
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
            notifyStoneReleased(player.getUuid());
        } else {
            heldByPlayer.put(player.getUuid(), current);
        }
    }

    private void logReleased(String player, String stone, String hand, String suffix) {
        logger.at(Level.INFO).log("[VoiceStone] %s released %s (%s%s)", player, stone, hand, suffix);
    }

    public boolean isHoldingStone(UUID uuid) {
        return heldByPlayer.containsKey(uuid);
    }

    public void addStoneReleasedCallback(Consumer<UUID> callback) {
        stoneReleasedCallbacks.add(callback);
    }

    private void notifyStoneReleased(UUID uuid) {
        for (Consumer<UUID> callback : stoneReleasedCallbacks) {
            callback.accept(uuid);
        }
    }

    public Set<UUID> getHolders() {
        return Set.copyOf(heldByPlayer.keySet());
    }

    public PlayerRef getPlayerRef(UUID uuid) {
        return playerRefs.get(uuid);
    }

    private <T extends ActiveSlotInventoryComponent> String activeStoneId(
            PlayerRef player, ComponentType<EntityStore, T> sectionType) {
        T section = player.getComponentConcurrent(sectionType);
        if (section == null) {
            return null;
        }
        ItemStack activeItem = section.getActiveItem();
        return VoiceStoneItems.isVoiceStone(activeItem) ? activeItem.getItemId() : null;
    }
}
