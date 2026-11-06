package com.ratnaraj.voicestone.gesture;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ratnaraj.voicestone.item.HeldStoneTracker;
import com.ratnaraj.voicestone.mana.ManaCosts;
import com.ratnaraj.voicestone.item.StoneLinks;
import com.ratnaraj.voicestone.VoiceStoneConfig;

import java.util.logging.Level;

public final class LinkToggles {

    private LinkToggles() {
    }

    public static boolean isCrouching(PlayerRef player) {
        var movementStates = player.getComponentConcurrent(MovementStatesComponent.getComponentType());

        if (movementStates == null) {
            return false;
        }

        return movementStates.getMovementStates().crouching;
    }

    public static void toggle(HytaleLogger logger, HeldStoneTracker tracker,
                              PlayerRef holder, PlayerRef target,
                              ItemStack stone, Store<EntityStore> store) {
        if (!ManaCosts.tryConsume(holder)) {
            FeedbackSound.play(
                    holder,
                    VoiceStoneConfig.getRefusalSoundEvent(),
                    VoiceStoneConfig.getRefusalVolumeDb(),
                    VoiceStoneConfig.getRefusalPitchSemitones(),
                    store
            );

            logger.at(Level.INFO).log(
                    "[VoiceStone] %s's link gesture refused: not enough mana",
                    holder.getUsername()
            );

            return;
        }

        boolean wasLinked = StoneLinks.get(stone).contains(target.getUuid());

        ItemStack linked = StoneLinks.toggle(stone, target.getUuid(), target.getUsername());

        FeedbackSound.play(
                holder,
                wasLinked ? VoiceStoneConfig.getUnlinkSoundEvent() : VoiceStoneConfig.getLinkSoundEvent(),
                store
        );

        if (!tracker.writeHeldStone(holder, linked)) {
            logger.at(Level.WARNING).log(
                    "[VoiceStone] %s's stone vanished between read and write, link toggle dropped",
                    holder.getUsername()
            );
        }
    }
}
