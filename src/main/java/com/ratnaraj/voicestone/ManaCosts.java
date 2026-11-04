package com.ratnaraj.voicestone;

import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatsModule;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;

import java.util.UUID;

public final class ManaCosts {

    private static final float MANA_LINK_COST = 30.0f;

    private ManaCosts() {
    }


    public static boolean isOutOfMana(UUID playerUuid) {
        ManaStat mana = resolve(Universe.get().getPlayer(playerUuid));

        if (mana == null) {
            return false;
        }

        EntityStatValue value = mana.stats().get(mana.index());

        return value != null && value.get() <= 0.0f;
    }


    public static boolean tryConsume(PlayerRef player) {
        ManaStat mana = resolve(player);

        if (mana == null) {
            return false;
        }

        EntityStatValue value = mana.stats().get(mana.index());

        if (value == null || value.get() < MANA_LINK_COST) {
            return false;
        }

        mana.stats().subtractStatValue(mana.index(), MANA_LINK_COST);
        return true;
    }

    private record ManaStat(EntityStatMap stats, int index) {}

    private static ManaStat resolve(PlayerRef player) {
        EntityStatsModule statsModule = EntityStatsModule.get();
        int manaIndex = DefaultEntityStatTypes.getMana();

        if (statsModule == null || manaIndex < 0 || player == null) {
            return null;
        }

        EntityStatMap stats = player.getComponentConcurrent(
                statsModule.getEntityStatMapComponentType());

        return stats == null ? null : new ManaStat(stats, manaIndex);
    }
}
