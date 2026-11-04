package com.ratnaraj.voicestone;

import com.hypixel.hytale.protocol.Position;
import com.hypixel.hytale.protocol.packets.stream.StreamType;
import com.hypixel.hytale.protocol.packets.voice.RelayedVoiceData;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.voice.PlayerVoiceFrame;
import com.hypixel.hytale.server.core.modules.voice.VoiceModule;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Routes the voice of a stone-holding speaker through the listeners' own
 * stones, positionally: the audio plays from the listener's stone, as if
 * the stone were the speaker. Two modes, decided in M5:
 *
 * <p>Links on the speaker's stone (targeted routing): the voice plays from
 * the linked players' stones, and nowhere else in the stone network. The
 * stone is a loudspeaker: anyone physically near a playing stone overhears,
 * mutual or one-way alike — there is no private channel (decided, revised
 * in M5: proximity to a stone always grants hearing, mirroring the
 * base-voice-chat principle that the plugin never removes a way to
 * hear). A linked player who holds no stone hears nothing through any
 * stone (the stone is the playback device, decided). The speaker's own
 * proximity delivery is never touched — the plugin never alters the base
 * voice chat (decided).
 *
 * <p>No links on the speaker's stone (broadcast): the voice plays from the
 * stone of every holder whose stone accepts this speaker — an unlinked
 * stone hears every broadcast, a linked stone hears only its linked
 * players (decided reception filter). The speaker's original proximity
 * delivery is never touched in this mode: physical hearing always works
 * for everyone.
 *
 * <p>Delivery is hand-crafted {@link RelayedVoiceData} packets rather than
 * native speakers: a hand-built packet carries the speaker's identity
 * (M4.1). The opus bytes, sequence and timestamp are extracted from the
 * frame once and passed explicitly to the packet builder. The router
 * never calls drop() — decided: the Voice Stone must not alter the base
 * voice chat in any mode. The router is stateless: one packet per frame
 * per (speaker, listener) pair, no stream bookkeeping.
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
        UUID speaker = frame.speaker().getUuid();

        ItemStack speakerStone = tracker.getHeldStone(speaker);
        if (speakerStone == null) {
            return;
        }

        if (voiceModule.isPlayerMuted(speaker)) {
            return;
        }

        VoiceModule.PositionSnapshot speakerSnapshot = voiceModule.getCachedPosition(speaker);
        if (speakerSnapshot == null) {
            return;
        }

        byte[] opus = frame.opus();
        short sequence = frame.sequenceNumber();
        int timestamp = frame.timestamp();

        List<UUID> links = StoneLinks.get(speakerStone);

        if (!links.isEmpty()) {
            routeToLinks(opus, sequence, timestamp, speaker, speakerSnapshot, links);
        } else {
            routeBroadcast(opus, sequence, timestamp, speaker, speakerSnapshot);
        }
    }

    /**
     * Targeted routing: the speaker's stone has links, the voice plays from
     * the linked players' stones — and nowhere else in the stone network.
     * The frame's own proximity delivery is left fully untouched: the
     * Voice Stone never alters the base voice chat (decided), so the
     * speaker is still heard normally by anyone physically near them; the
     * client's speakerId+sequence dedup keeps a linked listener standing
     * next to the speaker from hearing them twice.
     */
    private void routeToLinks(
            byte[] opus,
            short sequence,
            int timestamp,
            UUID speaker,
            VoiceModule.PositionSnapshot speakerSnapshot,
            List<UUID> links) {

        for (UUID linked : links) {
            if (tracker.getHeldStone(linked) == null) {
                continue;
            }

            VoiceModule.PositionSnapshot listenerSnapshot = voiceModule.getCachedPosition(linked);
            if (listenerSnapshot == null) {
                continue;
            }

            RelayedVoiceData packet = buildPacket(
                    opus, sequence, timestamp, speaker, speakerSnapshot, listenerSnapshot);

            Set<UUID> recipients = new HashSet<>(nearbyPlayers(listenerSnapshot));
            recipients.remove(speaker);

            deliver(packet, recipients);
        }
    }

    /**
     * Broadcast routing: the speaker's stone has no links, the voice plays
     * from the stone of every holder whose stone accepts this speaker. The
     * speaker's original proximity delivery is never touched here.
     */
    private void routeBroadcast(
            byte[] opus,
            short sequence,
            int timestamp,
            UUID speaker,
            VoiceModule.PositionSnapshot speakerSnapshot) {

        for (UUID listener : tracker.getHolders()) {
            if (listener.equals(speaker)) {
                continue;
            }

            ItemStack listenerStone = tracker.getHeldStone(listener);

            if (listenerStone == null) {
                continue;
            }

            if (!StoneLinks.receivesBroadcast(listenerStone, speaker)) {
                continue;
            }

            VoiceModule.PositionSnapshot listenerSnapshot = voiceModule.getCachedPosition(listener);
            if (listenerSnapshot == null) {
                continue;
            }

            RelayedVoiceData packet = buildPacket(
                    opus, sequence, timestamp, speaker, speakerSnapshot, listenerSnapshot);

            Set<UUID> recipients = new HashSet<>(nearbyPlayers(listenerSnapshot));
            recipients.remove(speaker);

            deliver(packet, recipients);
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
     * speaker).
     */
    private RelayedVoiceData buildPacket(
            byte[] opus,
            short sequence,
            int timestamp,
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

        return new RelayedVoiceData(
                speaker,
                entityId,
                sequence,
                timestamp,
                stonePosition,
                speakerSnapshot.isUnderwater(),
                opus);
    }

    /**
     * Writes one packet to each recipient's voice stream. A dumb pipe: the
     * caller decides who the recipients are (proximity around a stone, or
     * a single linked listener) and is responsible for excluding the
     * speaker — the packet's speakerId carries no such rule.
     */
    private void deliver(RelayedVoiceData packet, Set<UUID> recipients) {
        for (UUID recipient : recipients) {
            PlayerRef player = Universe.get().getPlayer(recipient);
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
