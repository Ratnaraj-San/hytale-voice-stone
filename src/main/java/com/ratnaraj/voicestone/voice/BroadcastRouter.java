package com.ratnaraj.voicestone.voice;

import com.hypixel.hytale.protocol.Position;
import com.hypixel.hytale.protocol.packets.stream.StreamType;
import com.hypixel.hytale.protocol.packets.voice.RelayedVoiceData;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.voice.PlayerVoiceFrame;
import com.hypixel.hytale.server.core.modules.voice.VoiceModule;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.ratnaraj.voicestone.item.HeldStoneTracker;
import com.ratnaraj.voicestone.mana.ManaCosts;
import com.ratnaraj.voicestone.item.StoneLinks;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Routes the voice of a stone-holding speaker through the listeners' own
 * stones, positionally: the audio plays from the listener's stone, as if
 * the stone were the speaker. With links on the speaker's stone, the voice
 * plays from the linked players' stones and nowhere else in the network
 * (the stone is a loudspeaker — anyone near a playing stone overhears,
 * and a linked player without a stone hears nothing). Without links, it
 * plays from every holder's stone whose reception filter accepts the
 * broadcast — a fully mutual stone refuses third-party broadcasts, any
 * non-mutual or unverifiable link keeps reception open. The speaker's
 * original proximity delivery is never touched in any mode, and the
 * router is stateless: one hand-built packet per frame per (speaker,
 * listener) pair, no stream bookkeeping.
 */
public final class BroadcastRouter {

    private final VoiceModule voiceModule;
    private final HeldStoneTracker tracker;

    public BroadcastRouter(VoiceModule voiceModule, HeldStoneTracker tracker) {
        this.voiceModule = voiceModule;
        this.tracker = tracker;
    }

    /** Handles one voice frame, called from the voice module's interceptor thread. */
    public void onFrame(PlayerVoiceFrame frame) {
        UUID speaker = frame.speaker().getUuid();

        ItemStack speakerStone = tracker.getHeldStone(speaker);
        if (speakerStone == null) {
            return;
        }

        if (voiceModule.isPlayerMuted(speaker)) {
            return;
        }

        if (ManaCosts.isOutOfMana(speaker)) {
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

    /** Targeted routing: each linked listener hears from their own stone, and no stone-holder is excluded from it. */
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

    /** Broadcast routing, filtered by each listener stone's reception filter. */
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

            if (!receivesBroadcast(listener, listenerStone, speaker)) {
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
     * The reception filter — privacy is a full mutual contract: a stone
     * whose every link is mutual (verified live: a partner not holding
     * their stone counts as non-mutual) refuses third-party broadcasts
     * and hears only its partners' targeted deliveries; any non-mutual
     * or unverifiable link keeps the stone's reception fully open. Only
     * broadcasts are filtered, never targeted delivery.
     */
    private boolean receivesBroadcast(UUID listener, ItemStack listenerStone, UUID speaker) {
        List<UUID> links = StoneLinks.get(listenerStone);

        if (links.isEmpty()) {
            return true;
        }

        for (UUID linked : links) {
            ItemStack linkedStone = tracker.getHeldStone(linked);

            if (linkedStone == null || !StoneLinks.get(linkedStone).contains(listener)) {
                return true;
            }
        }

        return links.contains(speaker);
    }

    /**
     * Builds the relay packet for one (speaker, listener) pair, audio
     * positioned at the listener's stone. The entityId is the speaker's
     * network id only when they are in the listener's world: ids are
     * per-world namespaces, and cross-world the client would resolve the
     * foreign id to an unrelated entity — zeroed, identity rides on the
     * speaker UUID alone.
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

    /** Writes one packet to each recipient's voice stream; the caller excludes the speaker from the recipients. */
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
     * Players in the same world as the stone and within the module's
     * hearing distance of it — the same selection the native router
     * applies around a positional speaker (custom packets bypass it, so
     * this is what keeps the loudspeaker leak working).
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
