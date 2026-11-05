package com.ratnaraj.voicestone;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.item.config.metadata.ItemDisplayMetadata;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.bson.BsonValue;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Reads and writes the link set of a Voice Stone, stored in the stack's
 * ItemStack metadata under two keys: "links", a BSON array of player UUID
 * strings — the source of truth, what the router follows — and
 * "linkNames", a BSON document of uuid-to-display-name recorded at link
 * time so the tooltip can show who a link belongs to even when the
 * player is offline. Links belong to the stone, not to its owner: the
 * metadata travels with the stack, so links survive relogs and giving
 * the stone away. A stone whose link set is empty is unlinked (broadcast
 * mode). Access goes through the typed metadata codec API; all writing
 * accessors return a NEW stack (withMetadata never mutates in place) —
 * the caller must put the returned stack back
 * (HeldStoneTracker.writeHeldStone).
 */
public final class StoneLinks {

    private static final String LINKS_KEY = "links";
    private static final String LINK_NAMES_KEY = "linkNames";
    private static final String LINKED_ITEM_SUFFIX = "_Linked";

    /** Codec for the stored link set: a BSON array of UUID strings. */
    private static final ArrayCodec<UUID> LINKS_CODEC = new ArrayCodec<>(Codec.UUID_STRING, UUID[]::new);

    private StoneLinks() {
    }

    /**
     * Returns the players this stone is linked to, in storage order.
     * Fresh but IMMUTABLE copy: wrap it in an ArrayList to modify the set.
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

    /** The display name recorded for a link at link time, or null — the fallback when the online lookup misses. */
    public static String getName(ItemStack stack, UUID player) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }

        BsonDocument names = stack.getFromMetadataOrNull(LINK_NAMES_KEY, Codec.BSON_DOCUMENT);

        if (names == null || !names.containsKey(player.toString())) {
            return null;
        }

        return names.getString(player.toString()).getValue();
    }

    /** Returns a copy of the stone with the target's link flipped — added with their name when absent, removed when present. */
    public static ItemStack toggle(ItemStack stack, UUID target, String name) {
        return get(stack).contains(target)
                ? unlink(stack, target)
                : link(stack, target, name);
    }

    /** Returns a copy of the stone with the target linked and their display name recorded. */
    public static ItemStack link(ItemStack stack, UUID target, String name) {
        List<UUID> links = new ArrayList<>(get(stack));

        if (!links.contains(target)) {
            links.add(target);
        }

        ItemStack stackWithLinks = withLinks(stack, links);
        ItemStack stackWithName = withName(stackWithLinks, target, name);

        return withVariant(withTooltip(stackWithName));
    }

    /** Returns a copy of the stone with the target's link and recorded name removed. */
    public static ItemStack unlink(ItemStack stack, UUID target) {
        List<UUID> links = new ArrayList<>(get(stack));
        links.remove(target);

        ItemStack stackWithLinks = withLinks(stack, links);
        ItemStack stackWithName = withName(stackWithLinks, target, null);

        return withVariant(withTooltip(stackWithName));
    }

    private static ItemStack withLinks(ItemStack stack, List<UUID> links) {
        if (links == null || links.isEmpty()) {
            return stack.withMetadata(LINKS_KEY, null);
        }

        BsonArray array = new BsonArray();
        for (UUID link : links) {
            array.add(new BsonString(link.toString()));
        }

        return stack.withMetadata(LINKS_KEY, array);
    }

    /**
     * Records (or removes, with a null name) one display name entry; the
     * names document is dropped entirely when it becomes empty. The decoded
     * document is cloned before mutation.
     */
    private static ItemStack withName(ItemStack stack, UUID player, String name) {
        BsonDocument names = stack.getFromMetadataOrNull(LINK_NAMES_KEY, Codec.BSON_DOCUMENT);

        if (name == null && names == null) {
            return stack;
        }

        BsonDocument updated = names == null ? new BsonDocument() : names.clone();

        if (name == null) {
            updated.remove(player.toString());
        } else {
            updated.put(player.toString(), new BsonString(name));
        }

        if (updated.isEmpty()) {
            return stack.withMetadata(LINK_NAMES_KEY, null);
        }

        return stack.withMetadata(LINK_NAMES_KEY, updated);
    }

    /** Applies the tooltip: the linked stone's description lists the linked players' names, "?" when none was recorded. */
    private static ItemStack withTooltip(ItemStack stack) {
        List<UUID> links = get(stack);

        if (links.isEmpty()) {
            return stack.withMetadata(ItemDisplayMetadata.KEY, (BsonValue) null);
        }

        String names = links.stream()
                .map(link -> {
                    String name = getName(stack, link);
                    return name == null ? "?" : name;
                })
                .collect(Collectors.joining(", "));

        ItemDisplayMetadata display = new ItemDisplayMetadata();

        Message baseDescription = Message.translation("server.items.Voice_Stone_Emerald.description");

        Message linksDescription = Message
                .translation("server.voicestone.tooltip.links")
                .param("names", names)
                .color(Color.WHITE);

        Message newDescription = Message.join(
                baseDescription,
                linksDescription
        );

        display.setDescription(newDescription);

        return stack.withMetadata(ItemDisplayMetadata.KEYED_CODEC, display);
    }

    /**
     * Returns the stone under the item id matching its link state: a
     * linked stone uses the _Linked visual variant, an unlinked one the
     * base item. The id constructor is the only route to a different item
     * and takes the metadata document whole. A target id that is not a
     * known Voice Stone (assets missing or renamed) leaves the stack
     * untouched rather than forging an invalid item.
     */
    private static ItemStack withVariant(ItemStack stack) {
        boolean linked = !get(stack).isEmpty();

        String id = stack.getItemId();
        boolean linkedId = id.endsWith(LINKED_ITEM_SUFFIX);

        if (linked == linkedId) {
            return stack;
        }

        String baseId = linkedId
                ? id.substring(0, id.length() - LINKED_ITEM_SUFFIX.length())
                : id;
        String targetId = linked ? baseId + LINKED_ITEM_SUFFIX : baseId;

        if (!VoiceStoneItems.IDS.contains(targetId)) {
            return stack;
        }

        return new ItemStack(targetId, stack.getQuantity(), stack.getMetadata());
    }
}
