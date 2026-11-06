package com.ratnaraj.voicestone.item;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EcsEvent;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Shared base of the two inventory-watching systems: feeds the held-stone
 * tracker whatever inventory event fired for the player.
 */
abstract class HeldStoneEventSystem<Event extends EcsEvent> extends EntityEventSystem<EntityStore, Event> {

    private final HeldStoneTracker tracker;

    HeldStoneEventSystem(Class<Event> eventType, HeldStoneTracker tracker) {
        super(eventType);
        this.tracker = tracker;
    }

    @Override
    public Query<EntityStore> getQuery() {
        return Player.getComponentType();
    }

    @Override
    public void handle(int index, ArchetypeChunk<EntityStore> chunk, Store<EntityStore> store,
                       CommandBuffer<EntityStore> commandBuffer, Event event) {
        PlayerRef player = chunk.getComponent(index, PlayerRef.getComponentType());
        if (player != null) {
            tracker.update(player);
        }
    }
}
