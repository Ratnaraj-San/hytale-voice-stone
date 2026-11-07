package com.ratnaraj.voicestone.item;

import com.hypixel.hytale.server.core.inventory.ItemStack;

import java.util.Map;
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
            "Voice_Stone_Ruby_Linked",
            "Voice_Stone_Diamond",
            "Voice_Stone_Diamond_Linked",
            "Voice_Stone_Topaz",
            "Voice_Stone_Topaz_Linked",
            "Voice_Stone_Void",
            "Voice_Stone_Void_Linked"
    );

    private static final Map<String, String> GEM_IDS = Map.of(
            "Voice_Stone_Emerald", "Rock_Gem_Emerald",
            "Voice_Stone_Zephyr",  "Rock_Gem_Zephyr",
            "Voice_Stone_Sapphire", "Rock_Gem_Sapphire",
            "Voice_Stone_Ruby",     "Rock_Gem_Ruby",
            "Voice_Stone_Diamond",  "Rock_Gem_Diamond",
            "Voice_Stone_Topaz",    "Rock_Gem_Topaz",
            "Voice_Stone_Void",     "Rock_Gem_Voidstone"
    );

    private static final String LINKED_SUFFIX = "_Linked";

    private VoiceStoneItems() {
    }

    public static boolean isVoiceStone(ItemStack stack) {
        return stack != null && !stack.isEmpty() && IDS.contains(stack.getItemId());
    }

    public static String getGemIdFor(String stoneId) {
        String idBase = stoneId.endsWith(LINKED_SUFFIX)
                ? stoneId.substring(0, stoneId.length() - LINKED_SUFFIX.length())
                : stoneId;

        return GEM_IDS.get(idBase);
    }
}
