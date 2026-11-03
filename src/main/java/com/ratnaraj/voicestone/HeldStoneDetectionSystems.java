package com.ratnaraj.voicestone;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EcsEvent;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.event.events.ecs.InventoryChangeEvent;
import com.hypixel.hytale.server.core.event.events.ecs.InventorySetActiveSlotEvent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * ECS event systems that watch player inventories and feed the held-stone
 * tracker. Active slot selection and inventory content changes together cover
 * every way a Voice Stone can start or stop being the held item.
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

final class ActiveSlotChangedSystem extends HeldStoneEventSystem<InventorySetActiveSlotEvent> {

    ActiveSlotChangedSystem(HeldStoneTracker tracker) {
        super(InventorySetActiveSlotEvent.class, tracker);
    }
}

final class InventoryChangedSystem extends HeldStoneEventSystem<InventoryChangeEvent> {

    InventoryChangedSystem(HeldStoneTracker tracker) {
        super(InventoryChangeEvent.class, tracker);
    }
}
