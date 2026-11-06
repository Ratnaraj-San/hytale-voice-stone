package com.ratnaraj.voicestone.item;

import com.hypixel.hytale.server.core.event.events.ecs.InventoryChangeEvent;

/**
 * Feeds the held-stone tracker when a player's inventory content changes —
 * a Voice Stone can start or stop being the held item that way.
 */
public final class InventoryChangedSystem extends HeldStoneEventSystem<InventoryChangeEvent> {

    public InventoryChangedSystem(HeldStoneTracker tracker) {
        super(InventoryChangeEvent.class, tracker);
    }
}
