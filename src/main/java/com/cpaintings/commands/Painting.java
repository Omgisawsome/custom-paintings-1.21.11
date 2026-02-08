package com.cpaintings.commands;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resourcesk.ResourceLocation;
import net.minecraft.server.level.ServerLevel; // FIX: ServerWorld -> ServerLevel
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.saveddata.maps.MapId; // FIX: MapItemId -> MapId
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.net.URI;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class Painting {

    // FIX: MapItemId does not exist; the component holds a MapId directly.
    @SuppressWarnings("unchecked")
    public static final DataComponentType<MapId> mapIdComponentType =
            (DataComponentType<MapId>) BuiltInRegistries.DATA_COMPONENT_TYPE.get(ResourceLocation.fromNamespaceAndPath("minecraft", "map_id"));

    @SuppressWarnings("unchecked")
    public static final DataComponentType<ItemLore> loreComponentType =
            (DataComponentType<ItemLore>) BuiltInRegistries.DATA_COMPONENT_TYPE.get(ResourceLocation.fromNamespaceAndPath("minecraft", "lore"));

    private static final Set<Long> usedChunks = new HashSet<>();

    private static long allocateChunk() {
        long base = 100000;
        long chunk;
        do {
            chunk = base++;
        } while (usedChunks.contains(chunk));
        usedChunks.add(chunk);
        return chunk;
    }

    private static final int[] MINECRAFT_MAP_COLORS = {
            0x000000, 0x7FB238, 0xF7E9A3, 0xC7C7C7, 0xFF0000, 0xA0A0FF, 0xA7A7A7, 0x007C00,
            0xFFFFFF, 0xA4A8B8, 0x976D4D, 0x707070, 0x4040FF, 0x8F7748, 0xFFFCF5, 0xD87F33,
            0xB24CD8, 0x6699D8, 0xE5E533, 0x7FCC19, 0xF27FA5, 0x4C4C4C, 0x999999, 0x4C7F99,
            0x7F3FB2, 0x334CB2, 0x664C33, 0x667F33, 0x993333, 0x191919, 0xFAEE4D, 0x5CDBD5,
            0x4A80FF, 0x00D93A, 0x815631, 0x700200, 0xD1B1A1, 0x9F5224, 0x95576C, 0x706C8A,
            0xBA8524, 0x677535, 0xA04D4E, 0x392923, 0x876B62, 0x575C5C, 0x7A4958, 0x4C3E5C,
            0x4C3223, 0x4C522A, 0x8E3C2E, 0x251610, 0xBD3031, 0x943F61, 0x5C191D, 0x167E86,
            0x3A8E8C, 0x562C3E, 0x14B485, 0x646464, 0xD8AF93, 0x7FA796
    };

    private static final float[] BRIGHTNESS_LEVELS = { 0.71f, 0.86f, 1.00f, 0.53f };

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                Commands.literal("painting")
                        .then(
                                Commands.argument("url", StringArgumentType.string())
                                        .executes(context -> {
                                            String url = StringArgumentType.getString(context, "url");
                                            CommandSourceStack source = context.getSource();
                                            new Thread(() -> processPainting(source, url, 1, 1)).start();
                                            return 1;
                                        })
                                        .then(
                                                Commands.argument("blocksx", IntegerArgumentType.integer(1))
                                                        .executes(context -> {
                                                            String url = StringArgumentType.getString(context, "url");
                                                            int blocksx = IntegerArgumentType.getInteger(context, "blocksx");
                                                            CommandSourceStack source = context.getSource();
                                                            new Thread(() -> processPainting(source, url, blocksx, 1)).start();
                                                            return 1;
                                                        })
                                                        .then(
                                                                Commands.argument("blocksy", IntegerArgumentType.integer(1))
                                                                        .executes(context -> {
                                                                            String url = StringArgumentType.getString(context, "url");
                                                                            int blocksx = IntegerArgumentType.getInteger(context, "blocksx");
                                                                            int blocksy = IntegerArgumentType.getInteger(context, "blocksy");
                                                                            CommandSourceStack source = context.getSource();
                                                                            new Thread(() -> processPainting(source, url, blocksx, blocksy)).start();
                                                                            return 1;
                                                                        })
                                                        )
                                        )
                        )
        ));
    }

    public static boolean isInventoryFull(Player player) {
        Inventory inventory = player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static void processPainting(CommandSourceStack source, String url, int blocksx, int blocksy) {
        try {
            BufferedImage originalImage = downloadImage(url);
            if (originalImage == null) throw new Exception("Image could not be read (null).");

            int totalWidth = 128 * blocksx;
            int totalHeight = 128 * blocksy;
            BufferedImage resized = resizeImage(originalImage, totalWidth, totalHeight);

            // FIX: ServerWorld -> ServerLevel
            ServerLevel world = source.getLevel();
            Player player = source.getPlayer();
            if (player == null) {
                source.sendFailure(Component.literal("Player not found."));
                return;
            }

            for (int y = 0; y < blocksy; y++) {
                int subY = (blocksy - 1 - y);
                for (int x = 0; x < blocksx; x++) {
                    BufferedImage tile = resized.getSubimage(x * 128, subY * 128, 128, 128);
                    long chunkPos = allocateChunk();

                    MapItemSavedData mapState = createMapStateForTile(world, chunkPos, tile);

                    // Mojang 1.21.11: Use getFreeMapId() and setMapData()
                    MapId mapId = world.getFreeMapId();
                    world.setMapData(mapId, mapState);

                    ItemStack mapItem = new ItemStack(Items.FILLED_MAP);
                    // FIX: MapItemId -> MapId (No wrapper needed)
                    mapItem.set(mapIdComponentType, mapId);

                    ItemLore lore = new ItemLore(Collections.singletonList(Component.literal("[" + x + "," + y + "]")));

                    if (blocksx > 1 && blocksy > 1) {
                        mapItem.set(loreComponentType, lore);
                    }

                    if (isInventoryFull(player)) {
                        player.drop(mapItem, false);
                    } else {
                        player.getInventory().add(mapItem);
                    }
                }
            }

            source.sendSuccess(
                    () -> Component.literal("Created " + (blocksx * blocksy) + " maps. Check your inventory!"),
                    false
            );
        } catch (Exception e) {
            source.sendFailure(Component.literal("An error occurred: " + e.getMessage()));
        }
    }

    private static BufferedImage downloadImage(String url) throws Exception {
        try (InputStream in = new URI(url).toURL().openStream()) {
            return ImageIO.read(in);
        }
    }

    private static BufferedImage resizeImage(BufferedImage original, int targetWidth, int targetHeight) {
        BufferedImage resized = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = resized.createGraphics();
        try {
            g.drawImage(original, 0, 0, targetWidth, targetHeight, null);
        } finally {
            g.dispose();
        }
        return resized;
    }

    private static MapItemSavedData createMapStateForTile(ServerLevel world, long chunkPos, BufferedImage image) {
        int centerX = (int) (chunkPos >> 32) << 4;
        int centerZ = (int) (chunkPos & 0xFFFFFFFFL) << 4;

        MapItemSavedData mapState = MapItemSavedData.create(
                centerX + 64,
                centerZ + 64,
                (byte) 2,
                false,
                false,
                world.dimension()
        );

        updateMapStateWithImage(mapState, image);
        return mapState;
    }

    private static void updateMapStateWithImage(MapItemSavedData mapState, BufferedImage image) {
        for (int z = 0; z < 128; z++) {
            for (int x = 0; x < 128; x++) {
                int argb = image.getRGB(x, z);
                int alpha = (argb >> 24) & 0xFF;
                if (alpha < 128) {
                    mapState.colors[x + z * 128] = 0;
                } else {
                    int colorIndex = mapColorToMapData(argb);
                    mapState.colors[x + z * 128] = (byte) colorIndex;
                }
            }
        }
        mapState.setDirty();
    }

    private static int mapColorToMapData(int argb) {
        Color target = new Color(argb, true);
        if (target.getAlpha() < 128) return 0;

        int bestIndex = 0;
        double bestDistance = Double.MAX_VALUE;

        for (int baseIndex = 1; baseIndex < MINECRAFT_MAP_COLORS.length; baseIndex++) {
            Color base = new Color(MINECRAFT_MAP_COLORS[baseIndex]);
            for (int shade = 0; shade < BRIGHTNESS_LEVELS.length; shade++) {
                float factor = BRIGHTNESS_LEVELS[shade];
                int r = (int) (base.getRed()   * factor);
                int g = (int) (base.getGreen() * factor);
                int b = (int) (base.getBlue()  * factor);

                double distance = colorDistance(target, new Color(r, g, b));
                if (distance < bestDistance) {
                    bestDistance = distance;
                    bestIndex = baseIndex * 4 + shade;
                }
            }
        }
        return bestIndex & 0xFF;
    }

    private static double colorDistance(Color c1, Color c2) {
        int redDiff   = c1.getRed()   - c2.getRed();
        int greenDiff = c1.getGreen() - c2.getGreen();
        int blueDiff  = c1.getBlue()  - c2.getBlue();
        return Math.sqrt(redDiff * redDiff + greenDiff * greenDiff + blueDiff * blueDiff);
    }
}