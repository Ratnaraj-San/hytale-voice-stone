package com.ratnaraj.voicestone;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.InteractionChain;
import com.hypixel.hytale.server.core.entity.InteractionManager;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.interaction.InteractionModule;
import com.hypixel.hytale.server.core.modules.voice.VoiceModule;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * The immersive link gesture: crouch + right-click with the stone in
 * hand toggles the link to the nearest player within reach. Fully
 * server-side, works in PvE — nothing here needs the client to run
 * plugin code.
 *
 * <p>Mechanism: the stone's item asset binds Secondary to the named
 * {@code Root_VoiceStone_Link} chain (the same way vanilla food binds its
 * consume root), so a right-click runs that chain — a 0.2s vanilla
 * Simple node, invisible data the stock client already understands. This
 * system watches the active chains of every stone holder through their
 * {@link InteractionManager} and reacts to a chain it has not seen yet
 * for the {@code Root_VoiceStone_Link} root. The 0.2s runtime keeps the
 * chain observable for several ticks and doubles as a subtle use
 * lockout, the gesture's only feedback window.
 *
 * <p>Guards: the holder must be crouching (a plain right-click is
 * ignored), and a target must be within reach — the nearest other
 * player, position from the voice module's cached snapshots. The toggle
 * itself is the shared machinery: {@link StoneLinks#toggle} and
 * {@link HeldStoneTracker#writeHeldStone}.
 */
public final class LinkChainSystem extends EntityTickingSystem<EntityStore> {

    private static final String LINK_ROOT_ID = "Root_VoiceStone_Link";
    private static final double REACH = 4.0;
    private static final double REACH_SQUARED = REACH * REACH;

    private final HytaleLogger logger;
    private final HeldStoneTracker tracker;
    private final VoiceModule voiceModule;

    /** Chain ids already handled per player, so one right-click toggles once. */
    private final Map<UUID, Set<Integer>> handledChains = new ConcurrentHashMap<>();

    public LinkChainSystem(HytaleLogger logger, HeldStoneTracker tracker, VoiceModule voiceModule) {
        this.logger = logger;
        this.tracker = tracker;
        this.voiceModule = voiceModule;
    }

    @Override
    public Query<EntityStore> getQuery() {
        return Player.getComponentType();
    }

    @Override
    public void tick(float deltaTime, int index, ArchetypeChunk<EntityStore> chunk,
                     Store<EntityStore> store, CommandBuffer<EntityStore> commandBuffer) {
        PlayerRef player = chunk.getComponent(index, PlayerRef.getComponentType());

        if (player == null) {
            return;
        }

        ItemStack stone = tracker.getHeldStone(player.getUuid());

        if (stone == null) {
            handledChains.remove(player.getUuid());
            return;
        }

        InteractionModule interactionModule = InteractionModule.get();

        if (interactionModule == null) {
            return;
        }

        InteractionManager manager =
                player.getComponentConcurrent(interactionModule.getInteractionManagerComponent());

        if (manager == null) {
            return;
        }

        Set<Integer> handled = handledChains.computeIfAbsent(player.getUuid(), uuid -> ConcurrentHashMap.newKeySet());

        for (InteractionChain chain : manager.getChains().values()) {
            if (handled.contains(chain.getChainId())) {
                continue;
            }

            handled.add(chain.getChainId());

            if (chain.getType() == InteractionType.Secondary
                    && chain.getRootInteraction() != null
                    && LINK_ROOT_ID.equals(chain.getRootInteraction().getId())) {
                handleGesture(player, stone);
            }
        }
    }

    /**
     * The gesture itself: crouch is required, the target is the nearest
     * other player within reach, the toggle is the shared link machinery.
     */
    private void handleGesture(PlayerRef player, ItemStack stone) {
        if (!isCrouching(player)) {
            return;
        }

        PlayerRef target = nearestPlayerWithinReach(player);

        if (target == null) {
            player.sendMessage(Message.raw("No player within reach of the stone."));
            return;
        }

        boolean wasLinked = StoneLinks.get(stone).contains(target.getUuid());

        ItemStack linked = StoneLinks.toggle(stone, target.getUuid(), target.getUsername());

        if (!tracker.writeHeldStone(player, linked)) {
            logger.at(Level.WARNING).log(
                    "[VoiceStone] %s's stone vanished between read and write, link toggle dropped",
                    player.getUsername()
            );
            return;
        }

        player.sendMessage(Message.raw(
                target.getUsername()
                + (wasLinked ? " is no longer" : " is now")
                + " linked to this stone."
        ));
    }

    private boolean isCrouching(PlayerRef player) {
        var movementStates = player.getComponentConcurrent(MovementStatesComponent.getComponentType());

        if (movementStates == null) {
            return false;
        }

        return movementStates.getMovementStates().crouching;
    }

    /**
     * The nearest other player within reach, positions from the voice
     * module's cached snapshots (the same source the router trusts).
     */
    private PlayerRef nearestPlayerWithinReach(PlayerRef actor) {
        VoiceModule.PositionSnapshot actorPosition = voiceModule.getCachedPosition(actor.getUuid());

        if (actorPosition == null) {
            return null;
        }

        UUID nearest = null;
        double nearestDistance = Double.MAX_VALUE;

        for (UUID candidate : voiceModule.getPlayerStates().keySet()) {
            if (candidate.equals(actor.getUuid())) {
                continue;
            }

            VoiceModule.PositionSnapshot position = voiceModule.getCachedPosition(candidate);

            if (position == null) {
                continue;
            }

            double distance = position.position().distanceSquared(actorPosition.position());

            if (distance <= REACH_SQUARED && distance < nearestDistance) {
                nearest = candidate;
                nearestDistance = distance;
            }
        }

        return nearest == null ? null : Universe.get().getPlayer(nearest);
    }
}
