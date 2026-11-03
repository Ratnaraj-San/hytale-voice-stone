package com.ratnaraj.voicestone;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.inventory.ActiveSlotInventoryComponent;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.logging.Level;

/**
 * The link gesture: crouch + left-click (attack) on a player while holding
 * a Voice Stone toggles the target's link on that stone. The strike is
 * cancelled, so the gesture is harmless — no damage, no knockback.
 *
 * <p>The damage pipeline is server-authoritative (unlike the interaction
 * system, which is client-authoritative and gates player targets — see the
 * M5 notes): the {@link Damage} event fires on the HIT entity for every
 * strike, and the attacker rides in {@code Damage.EntitySource}. Anything
 * that is not the gesture is left untouched: non-entity damage, non-player
 * attacker or target, attacker not crouching or without a stone in hand —
 * the event is only cancelled when a link is actually toggled, normal
 * combat included.
 *
 * <p>Known refinement, kept simple for now: projectile hits also carry an
 * EntitySource — shooting while crouched with a stone in hand would toggle
 * a link. Filter by {@code Damage.getCause()} / the INTERACTION_TYPE meta
 * if that matters in playtesting.
 */
public final class LinkGestureSystem extends EntityEventSystem<EntityStore, Damage> {

    private final HytaleLogger logger;
    private final HeldStoneTracker tracker;

    public LinkGestureSystem(HytaleLogger logger, HeldStoneTracker tracker) {
        super(Damage.class);
        this.logger = logger;
        this.tracker = tracker;
    }

    @Override
    public Query<EntityStore> getQuery() {
        return Player.getComponentType();
    }

    @Override
    public void handle(int index,
                       ArchetypeChunk<EntityStore> chunk,
                       Store<EntityStore> store,
                       CommandBuffer<EntityStore> commandBuffer,
                       Damage event) {

        if (!(event.getSource() instanceof Damage.EntitySource source)) {
            return;
        }

        PlayerRef attacker = playerRef(source.getRef());
        PlayerRef target = chunk.getComponent(index, PlayerRef.getComponentType());

        if (attacker == null || target == null || attacker.getUuid().equals(target.getUuid())) {
            return;
        }

        if (!isCrouching(attacker)) {
            return;
        }

        ItemStack stone = tracker.getHeldStone(attacker.getUuid());
        if (stone == null) {
            return;
        }

        toggleLink(attacker, target, stone);
        event.setCancelled(true);
    }

    private PlayerRef playerRef(Ref<EntityStore> ref) {
        return ref.getStore().getComponent(ref, PlayerRef.getComponentType());
    }

    /**
     * The crouch half of the gesture, read from the attacker's movement
     * input component. Null-guarded: an absent component means "not
     * crouching", the gesture simply does not apply.
     */
    private boolean isCrouching(PlayerRef player) {
        var movementStates = player.getComponentConcurrent(MovementStatesComponent.getComponentType());

        if (movementStates == null) {
            return false;
        }

        return movementStates.getMovementStates().crouching;
    }

    /**
     * Flips the link on the stone, puts the new stack back in the hand
     * that holds it, and logs the transition.
     */
    private void toggleLink(PlayerRef attacker, PlayerRef target, ItemStack stone) {
        boolean wasLinked = StoneLinks.get(stone).contains(target.getUuid());

        ItemStack linked = StoneLinks.toggle(stone, target.getUuid());

        if (!tracker.writeHeldStone(attacker, linked)) {
            logger.at(Level.WARNING).log(
                    "[VoiceStone] %s's stone vanished between read and write, link toggle dropped",
                    attacker.getUsername()
            );
            return;
        }

        logger.at(Level.INFO).log(
                "[VoiceStone] %s -> %s : %s on %s (%d links remaining)",
                attacker.getUsername(),
                target.getUsername(),
                wasLinked ? "unlink" : "link",
                stone.getItemId(),
                StoneLinks.get(linked).size()
        );
    }
}
