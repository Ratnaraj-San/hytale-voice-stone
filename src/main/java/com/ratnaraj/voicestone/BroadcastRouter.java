package com.ratnaraj.voicestone;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.modules.voice.PlayerVoiceFrame;
import com.hypixel.hytale.server.core.modules.voice.PositionalVoiceSpeaker;
import com.hypixel.hytale.server.core.modules.voice.VoiceModule;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Routes the voice of a stone-holding speaker to every other stone holder,
 * played positionally from each listener's own stone (M4 broadcast mode,
 * no links). The speaker's original proximity delivery is kept for
 * bystanders; stone holders are excluded from it so they never hear the
 * same voice twice.
 */
public final class BroadcastRouter {

    /**
     * One audio stream per (speaker, listener) pair. Two speakers talking to
     * the same listener must not share one stream, or their Opus frames
     * interleave into corrupted audio.
     */
    private record SpeakerKey(UUID speaker, UUID listener) {
    }

    private final VoiceModule voiceModule;
    private final HeldStoneTracker tracker;
    private final HytaleLogger logger;
    private final Map<SpeakerKey, PositionalVoiceSpeaker> speakers = new ConcurrentHashMap<>();

    public BroadcastRouter(VoiceModule voiceModule, HeldStoneTracker tracker, HytaleLogger logger) {
        this.voiceModule = voiceModule;
        this.tracker = tracker;
        this.logger = logger;
        tracker.addStoneReleasedCallback(this::onStoneReleased);
    }

    /**
     * Handles one voice frame, called from the voice module's interceptor
     * thread of the speaking player.
     */
    public void onFrame(PlayerVoiceFrame frame) {
        UUID speaker = frame.speaker().getUuid();

        if (!tracker.isHoldingStone(speaker)) { return; }

        // Never call frame.drop() here so bystanders keep hearing the speaker.
        tracker.getHolders().forEach(listener -> {
            if (listener.equals(speaker)) { return; }

            var snapshot = voiceModule.getCachedPosition(listener);

            if (snapshot == null) { return; }

            PositionalVoiceSpeaker stone = getOrCreateSpeaker(speaker, listener);

            if (stone == null) { return; }

            frame.excludeListener(listener);
            stone.setPosition(new Vector3d(snapshot.position()));
            stone.pushOpus(frame.opus());
        });
    }

    private PositionalVoiceSpeaker getOrCreateSpeaker(UUID speaker, UUID listener) {
        PlayerRef listenerRef = tracker.getPlayerRef(listener);
        var snapshot = voiceModule.getCachedPosition(listener);

        if (listenerRef == null || snapshot == null) {
            return null;
        }

        World world = listenerRef.getReference().getStore().getExternalData().getWorld();
        Vector3d position = new Vector3d(snapshot.position());

        return speakers.computeIfAbsent(
                new SpeakerKey(speaker, listener),
                key -> {
                    logger.at(Level.INFO).log(
                            "[VoiceStone] opened speaker %s -> %s at (%.1f, %.1f, %.1f)",
                            tracker.getPlayerRef(speaker).getUsername(),
                            listenerRef.getUsername(),
                            position.x(), position.y(), position.z());
                    return voiceModule.openPositionalVoice(world, position);
                });
    }

    /**
     * Release callback from the tracker: the player stopped being a stone
     * holder (released, disconnected). Closes every speaker involving them,
     * in either role — as a listener we were routing to, as a speaker we
     * were routing from.
     */
    private void onStoneReleased(UUID player) {
        List<SpeakerKey> toRemove = new ArrayList<>();

        for (SpeakerKey key : speakers.keySet()) {
            if (key.speaker().equals(player) || key.listener().equals(player)) {
                toRemove.add(key);
            }
        }

        for (SpeakerKey key : toRemove) {
            PositionalVoiceSpeaker speaker = speakers.remove(key);

            if (speaker != null) {
                speaker.close();
            }
        }

        if (!toRemove.isEmpty()) {
            logger.at(Level.INFO).log("[VoiceStone] closed %d speaker(s) for player %s", toRemove.size(), player);
        }
    }

    /** Closes every speaker */
    public void closeAll() {
        List<PositionalVoiceSpeaker> toClose = new ArrayList<>(speakers.values());

        speakers.clear();

        for (PositionalVoiceSpeaker speaker : toClose) {
            speaker.close();
        }
    }
}
