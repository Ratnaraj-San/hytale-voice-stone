package com.ratnaraj.voicestone.item;

import com.hypixel.hytale.assetstore.AssetPack;
import com.hypixel.hytale.assetstore.AssetPack.PackSource;
import com.hypixel.hytale.common.plugin.PluginManifest;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.AssetModule;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.bench.Bench;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.bench.CraftingBench;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.bson.BsonValue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;

/**
 * Adds the Voice Stones tab to the Arcane Bench without fighting other
 * mods over the bench item JSON.
 */
public final class ArcaneBenchTab {

    private static final Path BENCH_ITEM_PATH = Path.of("Server/Item/Items/Bench/Bench_Arcane.json");
    private static final String BENCH_ITEM_ID = "Bench_Arcane";
    private static final String TAB_ID = "Arcane_Voice_Stones";
    private static final String PACK_NAME = "VoiceStone_Tab";

    private ArcaneBenchTab() {
    }

    /**
     * Merges the Voice Stones tab into the Arcane Bench the server
     * actually loaded. Permissive: any failure logs a WARNING and
     * leaves the bench untouched — a missing crafting tab never
     * blocks the plugin.
     */
    public static void registerTab(HytaleLogger logger, Path dataDirectory, PluginManifest pluginManifest) {
        try {
            mergeTabOrLog(logger, dataDirectory, pluginManifest);
        } catch (Exception e) {
            logger.at(Level.WARNING).log("[VoiceStone] Could not merge the Voice Stones crafting tab into the Arcane bench: %s", e);
        }
    }

    private static void mergeTabOrLog(HytaleLogger logger, Path dataDirectory, PluginManifest pluginManifest) throws IOException {
        CraftingBench bench = loadedBench();

        if (bench == null) {
            logger.at(Level.WARNING).log("[VoiceStone] Arcane bench not loaded; the Voice Stones crafting tab stays as the plugin pack defines it");
            return;
        }

        if (hasTab(bench)) {
            return;
        }

        String winningJson = winningBenchJson(bench);

        if (winningJson == null) {
            logger.at(Level.WARNING).log("[VoiceStone] Could not find the loaded Arcane bench JSON to merge the Voice Stones tab into");
            return;
        }

        String mergedJson = mergedBenchJson(winningJson);

        Path packRoot = dataDirectory.resolve("generated/voicestone-tab");
        Path benchFile = packRoot.resolve(BENCH_ITEM_PATH);
        Files.createDirectories(benchFile.getParent());
        Files.writeString(benchFile, mergedJson);

        if (AssetModule.get().registerPack(PACK_NAME, packRoot, tabManifest(pluginManifest), PackSource.CLI)) {
            logger.at(Level.INFO).log("[VoiceStone] Voice Stones tab merged into the Arcane bench (generated pack at %s)", packRoot);
        } else {
            logger.at(Level.WARNING).log("[VoiceStone] The generated Voice Stones bench-tab pack was refused");
        }
    }

    /** The bench of the bench item as the asset registry loaded it — from whichever pack won. */
    private static CraftingBench loadedBench() {
        Item benchItem = Item.getAssetMap().getAsset(BENCH_ITEM_ID);

        if (benchItem == null) {
            return null;
        }

        BlockType block = BlockType.getAssetMap().getAsset(benchItem.getBlockId());

        if (block == null) {
            return null;
        }

        return block.getBench() instanceof CraftingBench craftingBench ? craftingBench : null;
    }

    private static boolean hasTab(CraftingBench bench) {
        for (var category : bench.getCategories()) {
            if (TAB_ID.equals(category.getId())) {
                return true;
            }
        }

        return false;
    }

    /**
     * The bench JSON the asset registry must have loaded: the one
     * pack, among those containing the bench file, whose category
     * ids match the parsed bench exactly.
     */
    private static String winningBenchJson(CraftingBench bench) {
        List<String> liveIds = new ArrayList<>();

        for (var category : bench.getCategories()) {
            liveIds.add(category.getId());
        }

        for (AssetPack pack : AssetModule.get().getAssetPacks()) {
            String json = readPackFile(pack);

            if (json != null && Objects.equals(jsonCategoryIds(json), liveIds)) {
                return json;
            }
        }

        return null;
    }

    private static String readPackFile(AssetPack pack) {
        try {
            Path benchFile = pack.resolve(BENCH_ITEM_PATH);

            if (benchFile == null || !Files.exists(benchFile)) {
                return null;
            }

            return Files.readString(benchFile);
        } catch (IOException e) {
            return null;
        }
    }

    private static List<String> jsonCategoryIds(String json) {
        BsonDocument bench = BsonDocument.parse(json);
        List<String> ids = new ArrayList<>();

        if (!bench.containsKey("BlockType")) {
            return null;
        }

        BsonDocument blockType = bench.getDocument("BlockType");

        if (!blockType.containsKey("Bench")) {
            return null;
        }

        for (BsonValue category : blockType.getDocument("Bench").getArray("Categories")) {
            ids.add(category.asDocument().getString("Id").getValue());
        }

        return ids;
    }

    private static String mergedBenchJson(String winningJson) {
        BsonDocument bench = BsonDocument.parse(winningJson);
        BsonArray categories = bench.getDocument("BlockType")
                .getDocument("Bench")
                .getArray("Categories");
        categories.add(voiceStonesTab());

        return bench.toJson();
    }

    private static BsonDocument voiceStonesTab() {
        return new BsonDocument()
                .append("Id", new BsonString(TAB_ID))
                .append("Name", new BsonString("server.benchCategories.voiceStones"))
                .append("Icon", new BsonString("Icons/CraftingCategories/Arcane/Voice_Stone_Emerald.png"));
    }

    private static PluginManifest tabManifest(PluginManifest plugin) {
        PluginManifest manifest = new PluginManifest();

        manifest.setGroup(plugin.getGroup());
        manifest.setName(PACK_NAME);
        manifest.setVersion(plugin.getVersion());
        manifest.setDescription(plugin.getDescription());
        manifest.setWebsite(plugin.getWebsite());
        manifest.setServerVersion(plugin.getServerVersion());
        manifest.setAuthors(plugin.getAuthors());

        return manifest;
    }
}
