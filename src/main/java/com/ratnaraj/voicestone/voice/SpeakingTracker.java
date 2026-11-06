package com.ratnaraj.voicestone.voice;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SpeakingTracker {

    private static final long FRESHNESS_MS = 500;

    private final Map<UUID, Instant> lastFrame = new ConcurrentHashMap<>();

    /** Voice executor thread: called for every intercepted frame. */
    public void mark(UUID speaker) {
        lastFrame.put(speaker, Instant.now());
    }

    /** ECS tick: is the speaker's stream alive right now? */
    public boolean isTransmitting(UUID speaker) {
        Instant last = lastFrame.get(speaker);
        return last != null && last.isAfter(Instant.now().minusMillis(FRESHNESS_MS));
    }

    public void clear(UUID speaker) {
        lastFrame.remove(speaker);
    }
}