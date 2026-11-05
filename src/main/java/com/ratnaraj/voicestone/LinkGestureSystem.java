package com.ratnaraj.voicestone;

import com.hypixel.hytale.common.util.AudioUtil;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.SoundCategory;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

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

    /**
     * Feedback sounds: the toggle sounds play exactly as their
     * SoundEvent assets define them — volume and pitch live in the
     * asset JSON, the packet stays at neutral — while the
     * mana-refusal sound supplements its asset with code-side
     * decibels/semitones (0 = neutral for both, converted to the
     * packet's linear rate via {@link AudioUtil}) to mark the
     * failure. The ids, volume and pitch are tunables in the
     * plugin config (M8).
     */

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

        PlayerRef holder = playerRef(source.getRef());
        PlayerRef target = chunk.getComponent(index, PlayerRef.getComponentType());

        if (holder == null || target == null || holder.getUuid().equals(target.getUuid())) {
            return;
        }

        if (!isCrouching(holder)) {
            return;
        }

        ItemStack stone = tracker.getHeldStone(holder.getUuid());

        if (stone == null) {
            return;
        }

        toggleLink(holder, target, stone, store);
        event.setCancelled(true);
    }

    private PlayerRef playerRef(Ref<EntityStore> ref) {
        return ref.getStore().getComponent(ref, PlayerRef.getComponentType());
    }

    /**
     * Plays a feedback sound 3d at the holder — everyone within
     * earshot hears the stone react, the holder like anyone else
     * (decided: every feedback sound is 3d, refusal included; a 2d
     * copy alongside would double the holder's playback). The
     * neutral overload plays the sound as its asset defines it;
     * the refusal adds code-side decibels/semitones to mark the
     * failed gesture. Falls back to 2d-to-player when the holder's
     * transform is unreadable — never lose the feedback.
     */
    private void playFeedbackSound(PlayerRef holder, String soundEventId, Store<EntityStore> store) {
        playFeedbackSound(holder, soundEventId, 0.0f, 0.0f, store);
    }

    private void playFeedbackSound(PlayerRef holder, String soundEventId,
                                    float volumeDb, float pitchSemitones,
                                    Store<EntityStore> store) {
        TransformComponent transform = holder.getComponentConcurrent(TransformComponent.getComponentType());
        Vector3d position = transform == null ? null : transform.getPosition();

        float volume = AudioUtil.decibelsToLinearGain(volumeDb);
        float pitch = AudioUtil.semitonesToLinearPitch(pitchSemitones);

        if (position == null) {
            SoundUtil.playSoundEvent2dToPlayer(
                    holder,
                    SoundEvent.getAssetMap().getIndex(soundEventId),
                    SoundCategory.SFX,
                    volume, pitch
            );
            return;
        }

        SoundUtil.playSoundEvent3d(
                SoundEvent.getAssetMap().getIndex(soundEventId),
                SoundCategory.SFX,
                position.x, position.y, position.z,
                volume, pitch,
                store
        );
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
     * that holds it, plays the toggle's feedback sound, and logs the
     * transition. The store rides along as the sound util's component
     * accessor.
     */
    private void toggleLink(PlayerRef holder, PlayerRef target, ItemStack stone, Store<EntityStore> store) {
        if (!ManaCosts.tryConsume(holder)) {
            playFeedbackSound(
                    holder,
                    VoiceStoneConfig.getRefusalSoundEvent(),
                    VoiceStoneConfig.getRefusalVolumeDb(),
                    VoiceStoneConfig.getRefusalPitchSemitones(),
                    store
            );

            logger.at(Level.INFO).log(
                    "[VoiceStone] %s's link gesture refused: not enough mana",
                    holder.getUsername()
            );

            return;
        }

        boolean wasLinked = StoneLinks.get(stone).contains(target.getUuid());

        ItemStack linked = StoneLinks.toggle(stone, target.getUuid(), target.getUsername());

        playFeedbackSound(
                holder,
                wasLinked ? VoiceStoneConfig.getUnlinkSoundEvent() : VoiceStoneConfig.getLinkSoundEvent(),
                store
        );

        if (!tracker.writeHeldStone(holder, linked)) {
            logger.at(Level.WARNING).log(
                    "[VoiceStone] %s's stone vanished between read and write, link toggle dropped",
                    holder.getUsername()
            );
            return;
        }



        logger.at(Level.INFO).log(
                "[VoiceStone] %s -> %s : %s on %s (%d links remaining)",
                holder.getUsername(),
                target.getUsername(),
                wasLinked ? "unlink" : "link",
                stone.getItemId(),
                StoneLinks.get(linked).size()
        );
    }
}
