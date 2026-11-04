package com.ratnaraj.voicestone;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
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

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class ManaDrainSystem extends EntityTickingSystem<EntityStore> {

    private static final float MANA_DRAIN_AMOUNT = 5.0f;
    private static final int MANA_DRAIN_FREQUENCY_MS = 1000;
    private static final long MANA_ZERO_LOCK_MS = 3000;

    private final HytaleLogger logger;
    private final HeldStoneTracker tracker;
    private final VoiceModule voiceModule;
    private final SpeakingTracker speakingTracker;

    private final Map<UUID, Instant> chrono = new ConcurrentHashMap<>();
    private final Map<UUID, Instant> zeroLocks = new ConcurrentHashMap<>();

    /** Diagnostic phase per player: gates log once per transition, not per tick. */
    private final Map<UUID, String> phase = new ConcurrentHashMap<>();

    public ManaDrainSystem(HytaleLogger logger, HeldStoneTracker tracker, VoiceModule voiceModule, SpeakingTracker speakingTracker) {
        this.logger = logger;
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

        if (zeroLock != null) {
            if (now.isAfter(zeroLock)) {
                zeroLocks.remove(playerUuid);
                stats.setStatChangeSuppressed(manaIndex, StatChangeDirection.RAISE, false);

                logger.at(Level.INFO).log("[ManaDrain] %s: mana regeneration resumed", player.getUsername());
            }
        }

        ItemStack stone = tracker.getHeldStone(playerUuid);

        if (stone == null) {
            enterPhase(player, "no stone in hand");
            chrono.remove(playerUuid);
            return;
        }

        VoicePlayerState state = voiceModule.getPlayerState(playerUuid);

        if (state == null) {
            enterPhase(player, "no voice player state");
            chrono.remove(playerUuid);
            return;
        }

        boolean isRoutingDisabled = state.isRoutingDisabled();
        boolean isVoiceEnabled = voiceModule.isVoiceEnabled();
        boolean isTransmitting = speakingTracker.isTransmitting(playerUuid);

        if (!isVoiceEnabled) {
            enterPhase(player, "voice chat disabled");
            chrono.remove(playerUuid);
            return;
        }

        if (isRoutingDisabled) {
            enterPhase(player, "voice routing disabled");
            chrono.remove(playerUuid);
            return;
        }

        if (!isTransmitting) {
            enterPhase(player, "mic silent");
            chrono.remove(playerUuid);
            return;
        }

        Instant start = chrono.get(playerUuid);

        if (start == null) {
            if (enterPhase(player, "timer started")) {
                EntityStatValue mana = manaIndex < 0 ? null : stats.get(manaIndex);
                logger.at(Level.INFO).log(
                        "[ManaDrain] mana stat index=%d, current=%s",
                        manaIndex,
                        mana == null ? "MISSING" : String.valueOf(mana.get())
                );
            }
            chrono.put(playerUuid, now);
            return;
        }

        if (Duration.between(start, now).toMillis() >= MANA_DRAIN_FREQUENCY_MS) {
            stats.subtractStatValue(manaIndex, MANA_DRAIN_AMOUNT);

            EntityStatValue mana = stats.get(manaIndex);

            logger.at(Level.INFO).log(
                    "[ManaDrain] %s drained %.1f mana, now %s",
                    player.getUsername(),
                    MANA_DRAIN_AMOUNT,
                    mana == null ? "?" : String.valueOf(mana.get())
            );

            if (mana != null && mana.get() <= 0.0f) {
                Instant previousLock = zeroLocks.put(playerUuid, now.plusMillis(MANA_ZERO_LOCK_MS));
                stats.setStatChangeSuppressed(manaIndex, StatChangeDirection.RAISE, true);

                if (previousLock == null) {
                    logger.at(Level.INFO).log("[ManaDrain] %s: mana exhausted, regeneration frozen", player.getUsername());
                }
            }

            chrono.put(playerUuid, now);
        }
    }

    /**
     * Logs a diagnostic phase change at INFO, exactly once per transition —
     * a per-tick log would spam the console ~20 times per second.
     */
    private boolean enterPhase(PlayerRef player, String phaseName) {
        String previous = phase.put(player.getUuid(), phaseName);

        if (previous != null && previous.equals(phaseName)) {
            return false;
        }

        logger.at(Level.INFO).log("[ManaDrain] %s: %s", player.getUsername(), phaseName);
        return true;
    }

    public void clear(UUID uuid) {
        chrono.remove(uuid);
        zeroLocks.remove(uuid);
    }

}
