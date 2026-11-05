package com.ratnaraj.voicestone;

import com.hypixel.hytale.server.core.inventory.ItemStack;

import java.util.Set;

public final class VoiceStoneItems {

    public static final Set<String> IDS = Set.of(
            "Voice_Stone_Emerald",
            "Voice_Stone_Emerald_Linked",
            "Voice_Stone_Zephyr",
            "Voice_Stone_Zephyr_Linked",
            "Voice_Stone_Sapphire",
            "Voice_Stone_Sapphire_Linked",
            "Voice_Stone_Ruby",
            "Voice_Stone_Ruby_Linked"
    );

    private VoiceStoneItems() {
    }

    public static boolean isVoiceStone(ItemStack stack) {
        return stack != null && !stack.isEmpty() && IDS.contains(stack.getItemId());
    }
}
