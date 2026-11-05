package com.ratnaraj.voicestone;

import com.hypixel.hytale.registry.Registration;
import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.modules.voice.VoiceModule;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;

import javax.annotation.Nonnull;
import java.util.logging.Level;

public class VoiceStone extends JavaPlugin {

    private static VoiceStone instance;

    public VoiceStone(@Nonnull JavaPluginInit init) {
        super(init);
        instance = this;
    }

    public static VoiceStone getInstance() {
        return instance;
    }

    private Registration voiceInterceptorRegistration;

    @Override
    protected void start() {
        VoiceStoneConfig.load(getLogger(), getDataDirectory());

        HeldStoneTracker heldStoneTracker = new HeldStoneTracker(getLogger());
        SpeakingTracker speakingTracker = new SpeakingTracker();

        getEntityStoreRegistry().registerSystem(new ActiveSlotChangedSystem(heldStoneTracker));
        getEntityStoreRegistry().registerSystem(new InventoryChangedSystem(heldStoneTracker));
        getEntityStoreRegistry().registerSystem(new LinkGestureSystem(getLogger(), heldStoneTracker));

        getCommandRegistry().registerCommand(new VoiceStoneCommand(getLogger(), heldStoneTracker));

        VoiceModule voiceModule = VoiceModule.get();

        if (voiceModule == null) {
            getLogger().at(Level.SEVERE).log("[VoiceStone] VoiceModule is null. Plugin could not be started.");
            return;
        }

        ManaDrainSystem manaDrainSystem = new ManaDrainSystem(getLogger(), heldStoneTracker, voiceModule, speakingTracker);

        registerEvents(heldStoneTracker, manaDrainSystem);

        BroadcastRouter broadcastRouter = new BroadcastRouter(voiceModule, heldStoneTracker);
        voiceInterceptorRegistration = voiceModule.addPlayerVoiceInterceptor(new VoiceFrameInterceptor(getLogger(), broadcastRouter, speakingTracker));

        getEntityStoreRegistry().registerSystem(new LinkChainSystem(getLogger(), heldStoneTracker, voiceModule));
        getEntityStoreRegistry().registerSystem(manaDrainSystem);

        getLogger().at(Level.INFO).log("[VoiceStone] Plugin enabled!");
    }

    @Override
    public void shutdown() {
        if (voiceInterceptorRegistration != null) {
            voiceInterceptorRegistration.unregister();
        }

        getLogger().at(Level.INFO).log("[VoiceStone] Plugin disabled!");
    }

    private void registerEvents(HeldStoneTracker heldStoneTracker, ManaDrainSystem manaDrainSystem) {
        getEventRegistry().registerAsync(PlayerConnectEvent.class, future -> future.thenApply(event -> {
            heldStoneTracker.reset(event.getPlayerRef().getUuid());
            heldStoneTracker.update(event.getPlayerRef());
            manaDrainSystem.clear(event.getPlayerRef().getUuid());

            return event;
        }));

        getEventRegistry().register(PlayerDisconnectEvent.class,
                event -> heldStoneTracker.onDisconnect(event.getPlayerRef()));
    }
}
