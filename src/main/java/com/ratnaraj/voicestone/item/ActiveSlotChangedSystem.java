package com.ratnaraj.voicestone.item;

import com.hypixel.hytale.server.core.event.events.ecs.InventorySetActiveSlotEvent;

/**
 * Feeds the held-stone tracker when a player changes their active slot —
 * a Voice Stone can start or stop being the held item that way.
 */
public final class ActiveSlotChangedSystem extends HeldStoneEventSystem<InventorySetActiveSlotEvent> {

    public ActiveSlotChangedSystem(HeldStoneTracker tracker) {
        super(InventorySetActiveSlotEvent.class, tracker);
    }
}
