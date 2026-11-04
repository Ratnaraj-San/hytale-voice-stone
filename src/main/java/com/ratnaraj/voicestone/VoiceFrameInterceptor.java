package com.ratnaraj.voicestone;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.modules.voice.PlayerVoiceFrame;
import com.hypixel.hytale.server.core.modules.voice.PlayerVoiceInterceptor;

import java.util.logging.Level;

/**
 * The M3 baseline: every captured voice frame is logged (FINE) and handed
 * to the router, which is where all the M4/M5 behavior lives.
 */
public class VoiceFrameInterceptor implements PlayerVoiceInterceptor {

    private final HytaleLogger logger;
    private final BroadcastRouter router;
    private final SpeakingTracker speakingTracker;

    public VoiceFrameInterceptor(HytaleLogger logger, BroadcastRouter router, SpeakingTracker speakingTracker) {
        this.logger = logger;
        this.router = router;
        this.speakingTracker = speakingTracker;
    }

    @Override
    public void intercept(PlayerVoiceFrame frame) {
        speakingTracker.mark(frame.speaker().getUuid());

        logger.at(Level.FINE).log(
                "[VoiceStone] frame from %s (%d bytes) at (%.1f, %.1f, %.1f)",
                frame.speaker().getUsername(),
                frame.opus().length,
                frame.position().x(),
                frame.position().y(),
                frame.position().z()
        );

        router.onFrame(frame);
    }
}
