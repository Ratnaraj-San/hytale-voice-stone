package com.ratnaraj.voicestone;

import com.hypixel.hytale.common.util.AudioUtil;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.SoundCategory;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

public class FeedbackSound {

    private FeedbackSound() {}

    public static void play(PlayerRef holder, String soundEventId, Store<EntityStore> store) {
        play(holder, soundEventId, 0.0f, 0.0f, store);
    }

    public static void play(PlayerRef holder, String soundEventId,
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
}
