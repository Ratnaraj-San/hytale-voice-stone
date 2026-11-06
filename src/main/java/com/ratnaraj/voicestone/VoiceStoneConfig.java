package com.ratnaraj.voicestone;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import org.bson.BsonDocument;
import org.bson.BsonDouble;
import org.bson.BsonString;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;

/**
 * The plugin's runtime tunables, read from config.json in the plugin's
 * data directory at boot — the file lives on the server, is created
 * with the defaults below the first time it is missing, and is never
 * synced to clients. Every entry is optional: a key missing from the
 * file keeps its default, and an unreadable or malformed file keeps
 * ALL defaults — a bad config never blocks the plugin from starting.
 *
 * <p>Parsed with the server jar's own org.bson BsonDocument (relaxed
 * extended JSON: plain numbers and strings, no quoted {@code f}
 * suffixes) — the same document API the item stack metadata uses.
 */
public final class VoiceStoneConfig {

    private static final String CONFIG_FILE = "config.json";

    /*
     * Single source of truth: the typed DEFAULT_ constants feed the field
     * initializers, the per-entry fallbacks and the generated default
     * file — a default value appears exactly once.
     */
    private static final float DEFAULT_MANA_DRAIN_PER_SECOND = 5.0f;
    private static final float DEFAULT_MANA_LINK_COST = 30.0f;
    private static final long DEFAULT_MANA_ZERO_REGEN_LOCK_MILLIS = 3000L;
    private static final String DEFAULT_LINK_SOUND_EVENT = "SFX_VoiceStone_Link";
    private static final String DEFAULT_UNLINK_SOUND_EVENT = "SFX_VoiceStone_Unlink";
    private static final String DEFAULT_REFUSAL_SOUND_EVENT = "SFX_Crystal_Build";
    private static final float DEFAULT_REFUSAL_VOLUME_DB = 0.0f;
    private static final float DEFAULT_REFUSAL_PITCH_SEMITONES = -12.0f;

    private static final String KEY_MANA_DRAIN_PER_SECOND = "manaDrainPerSecond";
    private static final String KEY_MANA_LINK_COST = "manaLinkCost";
    private static final String KEY_MANA_ZERO_REGEN_LOCK_MILLIS = "manaZeroRegenLockMillis";
    private static final String KEY_LINK_SOUND_EVENT = "linkSoundEvent";
    private static final String KEY_UNLINK_SOUND_EVENT = "unlinkSoundEvent";
    private static final String KEY_REFUSAL_SOUND_EVENT = "refusalSoundEvent";
    private static final String KEY_REFUSAL_VOLUME_DB = "refusalVolumeDb";
    private static final String KEY_REFUSAL_PITCH_SEMITONES = "refusalPitchSemitones";

    private static final String DEFAULT_CONFIG = """
            {
              "manaDrainPerSecond": %.1f,
              "manaLinkCost": %.1f,
              "manaZeroRegenLockMillis": %d,
              "linkSoundEvent": "%s",
              "unlinkSoundEvent": "%s",
              "refusalSoundEvent": "%s",
              "refusalVolumeDb": %.1f,
              "refusalPitchSemitones": %.1f
            }
            """.formatted(
            DEFAULT_MANA_DRAIN_PER_SECOND,
            DEFAULT_MANA_LINK_COST,
            DEFAULT_MANA_ZERO_REGEN_LOCK_MILLIS,
            DEFAULT_LINK_SOUND_EVENT,
            DEFAULT_UNLINK_SOUND_EVENT,
            DEFAULT_REFUSAL_SOUND_EVENT,
            DEFAULT_REFUSAL_VOLUME_DB,
            DEFAULT_REFUSAL_PITCH_SEMITONES
    );

    private static float manaDrainPerSecond = DEFAULT_MANA_DRAIN_PER_SECOND;
    private static float manaLinkCost = DEFAULT_MANA_LINK_COST;
    private static long manaZeroRegenLockMillis = DEFAULT_MANA_ZERO_REGEN_LOCK_MILLIS;
    private static String linkSoundEvent = DEFAULT_LINK_SOUND_EVENT;
    private static String unlinkSoundEvent = DEFAULT_UNLINK_SOUND_EVENT;
    private static String refusalSoundEvent = DEFAULT_REFUSAL_SOUND_EVENT;
    private static float refusalVolumeDb = DEFAULT_REFUSAL_VOLUME_DB;
    private static float refusalPitchSemitones = DEFAULT_REFUSAL_PITCH_SEMITONES;

    private VoiceStoneConfig() {
    }

    /**
     * Loads the config from the plugin data directory, creating the
     * default file on first boot. Must run before any system that
     * reads the values is constructed and registered.
     */
    public static void load(HytaleLogger logger, Path dataDirectory) {
        Path file = dataDirectory.resolve(CONFIG_FILE);

        try {
            if (!Files.exists(file)) {
                Files.createDirectories(dataDirectory);
                Files.writeString(file, DEFAULT_CONFIG, StandardCharsets.UTF_8);
                logger.at(Level.INFO).log("[VoiceStone] config file created with defaults at %s", file);
            }

            BsonDocument document = BsonDocument.parse(Files.readString(file, StandardCharsets.UTF_8));

            manaDrainPerSecond = (float) number(document, KEY_MANA_DRAIN_PER_SECOND, DEFAULT_MANA_DRAIN_PER_SECOND, logger);
            manaLinkCost = (float) number(document, KEY_MANA_LINK_COST, DEFAULT_MANA_LINK_COST, logger);
            manaZeroRegenLockMillis = Math.round(
                    number(document, KEY_MANA_ZERO_REGEN_LOCK_MILLIS, DEFAULT_MANA_ZERO_REGEN_LOCK_MILLIS, logger)
            );
            refusalVolumeDb = (float) number(document, KEY_REFUSAL_VOLUME_DB, DEFAULT_REFUSAL_VOLUME_DB, logger);
            refusalPitchSemitones = (float) number(document, KEY_REFUSAL_PITCH_SEMITONES, DEFAULT_REFUSAL_PITCH_SEMITONES, logger);

            linkSoundEvent = soundEvent(document, KEY_LINK_SOUND_EVENT, DEFAULT_LINK_SOUND_EVENT, logger);
            unlinkSoundEvent = soundEvent(document, KEY_UNLINK_SOUND_EVENT, DEFAULT_UNLINK_SOUND_EVENT, logger);
            refusalSoundEvent = soundEvent(document, KEY_REFUSAL_SOUND_EVENT, DEFAULT_REFUSAL_SOUND_EVENT, logger);

            logger.at(Level.INFO).log(
                    "[VoiceStone] config loaded from %s (drain %.1f/s, link cost %.1f, zero lock %dms)",
                    file, manaDrainPerSecond, manaLinkCost, manaZeroRegenLockMillis
            );
        } catch (IOException | RuntimeException e) {
            logger.at(Level.WARNING).log(
                    "[VoiceStone] config at %s unreadable (%s), using defaults",
                    file, e.getMessage()
            );
        }
    }

    /**
     * Reads a number entry. getNumber (not getDouble): a JSON integer
     * ({@code 3000}) parses as BsonInt32, which getDouble rejects — a
     * legible config value must not fall back to a default.
     */
    private static double number(BsonDocument document, String key, double fallback, HytaleLogger logger) {
        try {
            return document.getNumber(key, new BsonDouble(fallback)).doubleValue();
        } catch (RuntimeException e) {
            logger.at(Level.WARNING).log(
                    "[VoiceStone] config %s: not a number, keeping %s",
                    key, fallback
            );
            return fallback;
        }
    }

    /** Reads a sound event id and falls back to the default when the id resolves to no asset. */
    private static String soundEvent(BsonDocument document, String key, String fallback, HytaleLogger logger) {
        BsonString value = document.getString(key, new BsonString(fallback));

        if (SoundEvent.getAssetMap().getIndex(value.getValue()) < 0) {
            logger.at(Level.WARNING).log(
                    "[VoiceStone] config %s: unknown sound event '%s', keeping '%s'",
                    key, value.getValue(), fallback
            );
            return fallback;
        }

        return value.getValue();
    }

    public static float getManaDrainPerSecond() {
        return manaDrainPerSecond;
    }

    public static float getManaLinkCost() {
        return manaLinkCost;
    }

    public static long getManaZeroRegenLockMillis() {
        return manaZeroRegenLockMillis;
    }

    public static String getLinkSoundEvent() {
        return linkSoundEvent;
    }

    public static String getUnlinkSoundEvent() {
        return unlinkSoundEvent;
    }

    public static String getRefusalSoundEvent() {
        return refusalSoundEvent;
    }

    public static float getRefusalVolumeDb() {
        return refusalVolumeDb;
    }

    public static float getRefusalPitchSemitones() {
        return refusalPitchSemitones;
    }
}
