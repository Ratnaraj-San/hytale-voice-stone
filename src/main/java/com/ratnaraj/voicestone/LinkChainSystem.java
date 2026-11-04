package com.ratnaraj.voicestone;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.math.vector.Transform;
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
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * The immersive link gesture: crouch + right-click with the stone in
 * hand toggles the link to the player under the crosshair, within
 * reach (an angular aim cone — closest to the crosshair wins, so a
 * specific person can be picked out of a crowd). Fully
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
    private static final double AIM_CONE_DEGREES = 30.0;

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
    private void handleGesture(PlayerRef holder, ItemStack stone) {
        if (!isCrouching(holder)) {
            return;
        }

        PlayerRef target = aimedPlayerWithinReach(holder);

        if (target == null) {
            holder.sendMessage(Message.raw("No player under the stone's sight."));
            return;
        }

        if (!ManaCosts.tryConsume(holder)) {
            logger.at(Level.INFO).log(
                    "[VoiceStone] %s's link gesture refused: not enough mana",
                    holder.getUsername()
            );
            return;
        }

        boolean wasLinked = StoneLinks.get(stone).contains(target.getUuid());

        ItemStack linked = StoneLinks.toggle(stone, target.getUuid(), target.getUsername());

        if (!tracker.writeHeldStone(holder, linked)) {
            logger.at(Level.WARNING).log(
                    "[VoiceStone] %s's stone vanished between read and write, link toggle dropped",
                    holder.getUsername()
            );
            return;
        }

        holder.sendMessage(Message.raw(
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
     * The player under the crosshair, within reach: candidates are the
     * voice module's snapshots (eye-height positions, same world as the
     * actor, not dead), scored by the angle between the actor's head
     * direction and the direction to them; the best candidate must sit
     * inside the aim cone. This is an angular check, not a physical
     * raycast — walls do not block, at 4 blocks of reach that is a
     * tolerable simplification.
     */
    private PlayerRef aimedPlayerWithinReach(PlayerRef actor) {
        VoiceModule.PositionSnapshot actorSnapshot = voiceModule.getCachedPosition(actor.getUuid());
        Rotation3f headRotation = actor.getHeadRotation();

        if (actorSnapshot == null || headRotation == null) {
            return null;
        }

        Vector3dc origin = actorSnapshot.position();
        Vector3d look = Transform.getDirection(headRotation.x, headRotation.y).normalize(new Vector3d());

        UUID best = null;
        double bestDot = Math.cos(Math.toRadians(AIM_CONE_DEGREES));

        for (UUID candidate : voiceModule.getPlayerStates().keySet()) {
            if (candidate.equals(actor.getUuid())) {
                continue;
            }

            VoiceModule.PositionSnapshot snapshot = voiceModule.getCachedPosition(candidate);

            if (snapshot == null || snapshot.isDead() || !snapshot.worldId().equals(actorSnapshot.worldId())) {
                continue;
            }

            Vector3d toTarget = snapshot.position().sub(origin, new Vector3d());

            if (toTarget.lengthSquared() > REACH_SQUARED) {
                continue;
            }

            double dot = toTarget.lengthSquared() == 0.0
                    ? 1.0
                    : look.dot(toTarget.normalize());

            if (dot > bestDot) {
                best = candidate;
                bestDot = dot;
            }
        }

        return best == null ? null : Universe.get().getPlayer(best);
    }
}
