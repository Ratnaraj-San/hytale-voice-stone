package com.ratnaraj.voicestone;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.modules.voice.PlayerVoiceFrame;
import com.hypixel.hytale.server.core.modules.voice.PlayerVoiceInterceptor;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import org.joml.Vector3dc;

import java.util.UUID;
import java.util.logging.Level;

public class VoiceFrameInterceptor implements PlayerVoiceInterceptor {

    private final HytaleLogger logger;

    public VoiceFrameInterceptor(HytaleLogger logger) {
        this.logger = logger;
    }

    @Override
    public void intercept(PlayerVoiceFrame playerVoiceFrame) {
        PlayerRef playerRef = playerVoiceFrame.speaker();
        byte[] opus = playerVoiceFrame.opus();
        Vector3dc position = playerVoiceFrame.position();

        UUID playerUUID = playerRef.getUuid();
        String playerName = playerRef.getUsername();

        int opusLength = opus.length;

        double positionX = position.x();
        double positionY = position.y();
        double positionZ = position.z();

        logger.at(Level.INFO).log(
                "[VoiceStone] frame from %s | %s (%d bytes) at (%.1f, %.1f, %.1f)",
                playerName,
                playerUUID.toString(),
                opusLength,
                positionX,
                positionY,
                positionZ
                );
    }
}
