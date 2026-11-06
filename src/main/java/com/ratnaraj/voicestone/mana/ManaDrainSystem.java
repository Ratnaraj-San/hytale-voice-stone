package com.ratnaraj.voicestone.mana;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatsModule;
import com.hypixel.hytale.server.core.modules.entitystats.StatChangeDirection;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.modules.time.TimeResource;
import com.hypixel.hytale.server.core.modules.voice.VoiceModule;
import com.hypixel.hytale.server.core.modules.voice.VoicePlayerState;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ratnaraj.voicestone.item.HeldStoneTracker;
import com.ratnaraj.voicestone.voice.SpeakingTracker;
import com.ratnaraj.voicestone.VoiceStoneConfig;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Drains mana from every stone holder while they actively transmit: one
 * drain per full second of continuous transmission, amount from the config.
 * A drain that reaches zero freezes regeneration for the configured window
 * via the stat map's RAISE suppression — flicker-free because suppression
 * rejects the regen writes entirely instead of clamping after the fact.
 */
public final class ManaDrainSystem extends EntityTickingSystem<EntityStore> {

    /** The drain interval is fixed at one second (decided) — only the amount is tunable. */
    private static final int MANA_DRAIN_FREQUENCY_MS = 1000;

    private final HeldStoneTracker tracker;
    private final VoiceModule voiceModule;
    private final SpeakingTracker speakingTracker;

    private final Map<UUID, Instant> chrono = new ConcurrentHashMap<>();
    private final Map<UUID, Instant> zeroLocks = new ConcurrentHashMap<>();

    public ManaDrainSystem(HeldStoneTracker tracker, VoiceModule voiceModule, SpeakingTracker speakingTracker) {
        this.tracker = tracker;
        this.voiceModule = voiceModule;
        this.speakingTracker = speakingTracker;
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

        UUID playerUuid = player.getUuid();

        EntityStatMap stats = chunk.getComponent(index, EntityStatsModule.get().getEntityStatMapComponentType());

        if (stats == null) { return; }


        int manaIndex = DefaultEntityStatTypes.getMana();
        Instant now = store.getResource(TimeResource.getResourceType()).getNow();

        Instant zeroLock = zeroLocks.get(playerUuid);

        if (zeroLock != null && now.isAfter(zeroLock)) {
            zeroLocks.remove(playerUuid);
            stats.setStatChangeSuppressed(manaIndex, StatChangeDirection.RAISE, false);
        }

        if (!isTransmitting(playerUuid)) {
            chrono.remove(playerUuid);
            return;
        }

        Instant start = chrono.get(playerUuid);

        if (start == null) {
            chrono.put(playerUuid, now);
            return;
        }

        if (Duration.between(start, now).toMillis() >= MANA_DRAIN_FREQUENCY_MS) {
            stats.subtractStatValue(manaIndex, VoiceStoneConfig.getManaDrainPerSecond());

            EntityStatValue mana = stats.get(manaIndex);

            if (mana != null && mana.get() <= 0.0f) {
                zeroLocks.put(playerUuid, now.plusMillis(VoiceStoneConfig.getManaZeroRegenLockMillis()));
                stats.setStatChangeSuppressed(manaIndex, StatChangeDirection.RAISE, true);
            }

            chrono.put(playerUuid, now);
        }
    }

    /**
     * A fresh intercepted frame implies voice chat enabled and a live mic
     * stream — empty frames are rejected upstream of the interceptors.
     */
    private boolean isTransmitting(UUID playerUuid) {
        VoicePlayerState state = voiceModule.getPlayerState(playerUuid);

        return voiceModule.isVoiceEnabled()
                && state != null
                && !state.isRoutingDisabled()
                && speakingTracker.isTransmitting(playerUuid)
                && tracker.getHeldStone(playerUuid) != null;
    }

    public void clear(UUID uuid) {
        chrono.remove(uuid);
        zeroLocks.remove(uuid);
    }

}
