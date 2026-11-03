package com.ratnaraj.voicestone;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;

import javax.annotation.Nonnull;
import java.nio.file.Path;
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

    @Override
    protected void setup() {
        Path pluginDataDir = getDataDirectory();
    }

    @Override
    protected void start() {
        getLogger().at(Level.INFO).log("[VoiceStone] Plugin enabled!");
    }

    @Override
    public void shutdown() {
        getLogger().at(Level.INFO).log("[VoiceStone] Plugin disabled!");
    }

    private void registerEvents() {
    }

    private void registerCommands() {
    }

}
