package com.ratnaraj.voicestone;

import com.hypixel.hytale.protocol.Position;
import com.hypixel.hytale.protocol.packets.stream.StreamType;
import com.hypixel.hytale.protocol.packets.voice.RelayedVoiceData;
import com.hypixel.hytale.server.core.modules.voice.PlayerVoiceFrame;
import com.hypixel.hytale.server.core.modules.voice.VoiceModule;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Routes the voice of a stone-holding speaker to every other stone holder,
 * played positionally from each listener's own stone (M4 broadcast mode,
 * no links). The speaker's original proximity delivery is never touched:
 * physical hearing always works normally for everyone, and a stone holder
 * standing next to a stone-holding speaker hears them both physically and
 * through their own stone — preventing that double-hearing is the players'
 * business, not the plugin's (decided).
 *
 * <p>Delivery is hand-crafted {@link RelayedVoiceData} packets rather than
 * native {@code PositionalVoiceSpeaker}s: the native positional speaker is
 * anonymous by design (entityId = 0, shown as "Unknown" in the client's
 * talking indicator), while a hand-built packet can carry the speaker's
 * identity. Packets are delivered to the stone holder AND to anyone
 * physically near their stone, which reimplements the native proximity
 * routing and preserves the designed leak: anyone near a playing stone
 * overhears it.
 *
 * <p>Identity follows the native semantics exactly (verified against
 * {@code PlayerVoiceFrame.speakerId()}): the packet's speakerId is the
 * speaker's player UUID, resolved by the client from its player list at any
 * distance, and the entityId is the speaker's network entity id, which
 * carries the in-world talking indicator when the entity is loaded for the
 * receiver. The router is stateless: one packet per frame per (speaker,
 * listener) pair, no stream bookkeeping.
 */
public final class BroadcastRouter {

    private final VoiceModule voiceModule;
    private final HeldStoneTracker tracker;

    public BroadcastRouter(VoiceModule voiceModule, HeldStoneTracker tracker) {
        this.voiceModule = voiceModule;
        this.tracker = tracker;
    }

    /**
     * Handles one voice frame, called from the voice module's interceptor
     * thread of the speaking player.
     */
    public void onFrame(PlayerVoiceFrame frame) {
        // Never call frame.drop() here so bystanders keep hearing the speaker.
        UUID speaker = frame.speaker().getUuid();

        if (!tracker.isHoldingStone(speaker)) {
            return;
        }

        if (voiceModule.isPlayerMuted(speaker)) {
            return;
        }

        VoiceModule.PositionSnapshot speakerSnapshot = voiceModule.getCachedPosition(speaker);
        if (speakerSnapshot == null) {
            return;
        }

        for (UUID listener : tracker.getHolders()) {
            if (listener.equals(speaker)) {
                continue;
            }

            VoiceModule.PositionSnapshot listenerSnapshot = voiceModule.getCachedPosition(listener);
            if (listenerSnapshot == null) {
                continue;
            }

            deliverFrame(frame, speaker, speakerSnapshot, listenerSnapshot);
        }
    }

    /**
     * Builds the relay packet for one (speaker, listener) pair: the audio is
     * positioned at the listener's stone, speakerId is the speaker's player
     * UUID (identity at any distance) and entityId their network entity id
     * (in-world indicator when loaded) — but only when the speaker is in
     * the listener's world: network entity ids are per-world, so cross-world
     * the client would resolve the id to an unrelated entity of the
     * receiver's own world (observed: the listener seeing themselves as the
     * speaker). Delivered to everyone near the stone, not just the holder —
     * that is the leak.
     */
    private void deliverFrame(
            PlayerVoiceFrame frame,
            UUID speaker,
            VoiceModule.PositionSnapshot speakerSnapshot,
            VoiceModule.PositionSnapshot listenerSnapshot) {

        Position stonePosition = new Position();
        stonePosition.x = listenerSnapshot.position().x();
        stonePosition.y = listenerSnapshot.position().y();
        stonePosition.z = listenerSnapshot.position().z();

        int entityId = speakerSnapshot.worldId().equals(listenerSnapshot.worldId())
                ? speakerSnapshot.networkId()
                : 0;

        RelayedVoiceData packet = new RelayedVoiceData(
                speaker,
                entityId,
                frame.sequenceNumber(),
                frame.timestamp(),
                stonePosition,
                speakerSnapshot.isUnderwater(),
                frame.opus());

        for (UUID near : nearbyPlayers(listenerSnapshot)) {
            if (near.equals(speaker)) {
                continue;
            }

            PlayerRef player = Universe.get().getPlayer(near);
            if (player == null) {
                continue;
            }

            var channel = player.getPacketHandler().getChannel(StreamType.Voice);
            if (channel.isActive()) {
                channel.writeAndFlush(packet);
            }
        }
    }

    /**
     * Players in the same world as the stone and within the module's hearing
     * distance of it — the same selection the native router applies around a
     * positional speaker. The stone holder is at distance zero, always in.
     */
    private Set<UUID> nearbyPlayers(VoiceModule.PositionSnapshot stone) {
        double maxDistanceSquared = squared(voiceModule.getMaxHearingDistance());
        UUID worldId = stone.worldId();

        Set<UUID> nearby = new HashSet<>();
        for (UUID uuid : voiceModule.getPlayerStates().keySet()) {
            VoiceModule.PositionSnapshot position = voiceModule.getCachedPosition(uuid);
            if (position == null || !position.worldId().equals(worldId)) {
                continue;
            }
            if (position.position().distanceSquared(stone.position()) <= maxDistanceSquared) {
                nearby.add(uuid);
            }
        }
        return nearby;
    }

    private static double squared(double value) {
        return value * value;
    }
}
