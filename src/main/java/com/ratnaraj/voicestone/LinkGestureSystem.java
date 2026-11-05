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
 * a Voice Stone toggles the target's link on that stone. The damage
 * pipeline is server-authoritative, so the strike is cancelled for every
 * recognized gesture — harmless, no damage, no knockback. Only the
 * recognized gesture is cancelled; anything else (normal combat, PvE
 * hits) is left untouched.
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
     * Plays a feedback sound 3d at the holder — everyone within earshot
     * hears the stone react — falling back to 2d-to-player when the
     * holder's transform is unreadable. Volume and pitch are decibels and
     * semitones: AudioUtil converts them to the packet's linear rate, and
     * passing dB/semitone values raw produces silence.
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

    private boolean isCrouching(PlayerRef player) {
        var movementStates = player.getComponentConcurrent(MovementStatesComponent.getComponentType());

        if (movementStates == null) {
            return false;
        }

        return movementStates.getMovementStates().crouching;
    }

    /**
     * Consumes the mana cost, flips the link on the stone, writes the new
     * stack back into the holding hand and plays the toggle's feedback
     * sound.
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
