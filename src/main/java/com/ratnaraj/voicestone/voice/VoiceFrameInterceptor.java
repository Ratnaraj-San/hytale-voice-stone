package com.ratnaraj.voicestone.voice;

import com.hypixel.hytale.server.core.modules.voice.PlayerVoiceFrame;
import com.hypixel.hytale.server.core.modules.voice.PlayerVoiceInterceptor;

/**
 * Marks every captured frame as fresh speech (the mana drain's activity
 * signal) and hands the frame to the router, where all routing behavior lives.
 */
public class VoiceFrameInterceptor implements PlayerVoiceInterceptor {

    private final BroadcastRouter router;
    private final SpeakingTracker speakingTracker;

    public VoiceFrameInterceptor(BroadcastRouter router, SpeakingTracker speakingTracker) {
        this.router = router;
        this.speakingTracker = speakingTracker;
    }

    @Override
    public void intercept(PlayerVoiceFrame frame) {
        speakingTracker.mark(frame.speaker().getUuid());
        router.onFrame(frame);
    }
}
