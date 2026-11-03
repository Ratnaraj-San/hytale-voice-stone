package com.ratnaraj.voicestone;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.server.core.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Reads and writes the link set of a Voice Stone, stored in the stone's
 * ItemStack metadata: the "links" key holds a BSON array of player UUID
 * strings. Links belong to the stone, not to its owner — the metadata
 * travels with the stack, so links survive relogs and giving the stone
 * away. A stone whose link set is empty is unlinked (broadcast mode).
 *
 * <p>Access goes through the typed metadata codec API
 * (getFromMetadataOrNull / withMetadata with a Codec) — the raw
 * ItemStack.getMetadata() is deprecated and clone-heavy. All accessors
 * return fresh copies and the writing ones return a NEW stack
 * (withMetadata never mutates in place): the caller must put the returned
 * stack back where the old one was held.
 */
public final class StoneLinks {

    /** Metadata key under which the link set is stored in the stone's metadata. */
    private static final String LINKS_KEY = "links";

    /** Codec for the stored link set: a BSON array of UUID strings. */
    private static final ArrayCodec<UUID> LINKS_CODEC = new ArrayCodec<>(Codec.UUID_STRING, UUID[]::new);

    private StoneLinks() {
    }

    /**
     * Returns the players this stone is linked to, in storage order.
     * Fresh but IMMUTABLE copy: wrap it in an ArrayList to modify the set.
     *
     * @param stack the stone's ItemStack, may be null or metadata-less
     * @return the link set, empty when the stone has no links; never null
     */
    public static List<UUID> get(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return List.of();
        }

        UUID[] links = stack.getFromMetadataOrNull(LINKS_KEY, LINKS_CODEC);

        if (links == null || links.length == 0) {
            return List.of();
        }
        return List.of(links);
    }

    /**
     * Returns a copy of the stone with its link set replaced wholesale.
     * Does not mutate the given stack — the returned stack must be written
     * back by the caller (HeldStoneTracker.writeHeldStone).
     *
     * @param stack the stone's ItemStack
     * @param links the new link set; empty or null clears the links
     *              (the key is removed: an unlinked stone has no links key)
     * @return a new ItemStack carrying the new link set
     */
    public static ItemStack withLinks(ItemStack stack, List<UUID> links) {
        if (links == null || links.isEmpty()) {
            return stack.withMetadata(LINKS_KEY, null);
        }
        return stack.withMetadata(LINKS_KEY, LINKS_CODEC, links.toArray(UUID[]::new));
    }

    /**
     * Convenience toggle (the decided link gesture): returns a copy of the
     * stone with the target's link flipped — added if absent, removed if
     * present.
     *
     * @param stone  the stone's ItemStack
     * @param target the player to link or unlink
     * @return a new ItemStack with the toggled link set
     */
    public static ItemStack toggle(ItemStack stone, UUID target) {
        List<UUID> links = new ArrayList<>(get(stone));

        if (links.contains(target)) {
            links.remove(target);
        } else {
            links.add(target);
        }

        return withLinks(stone, links);
    }

    /**
     * Decides whether a frame from the given speaker should play through a
     * listener's stone in broadcast mode (speaker's stone has no links):
     * an unlinked stone hears every broadcast, a linked stone hears only
     * the players it is linked to (decided reception filter).
     *
     * @param listenerStone the listening stone's ItemStack
     * @param speaker       the speaking player
     * @return true if the listener's stone may play this speaker's broadcast
     */
    public static boolean receivesBroadcast(ItemStack listenerStone, UUID speaker) {
        List<UUID> links = get(listenerStone);

        return links.isEmpty() || links.contains(speaker);
    }

    /**
     * Decides whether a one-way link is mutual: the speaker linked the
     * listener, and the listener's stone links the speaker back. Mutual
     * links route privately (no positional leak around the listener's
     * stone), one-way links leak (decided).
     *
     * @param listenerStone the listening stone's ItemStack
     * @param speaker       the speaking player
     * @return true if the link is mutual
     */
    public static boolean isMutual(ItemStack listenerStone, UUID speaker) {
        List<UUID> listenerStonelinks = get(listenerStone);

        return listenerStonelinks.contains(speaker);
    }
}
