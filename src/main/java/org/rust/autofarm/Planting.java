package org.rust.autofarm;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class Planting {
    static final List<String> DEFAULT_SEEDS = List.of("minecraft:wheat_seeds");

    private static @Nullable List<Item> selectable;

    private Planting() {}

    public static List<Item> selectable() {
        if (selectable == null) {
            List<Item> list = new ArrayList<>();
            for (Item item : BuiltInRegistries.ITEM) {
                if (item instanceof BlockItem blockItem && isGroundCrop(blockItem.getBlock()))
                    list.add(item);
            }
            list.sort(Comparator.comparing(i -> BuiltInRegistries.ITEM.getKey(i).toString()));
            selectable = List.copyOf(list);
        }
        return selectable;
    }

    private static boolean isGroundCrop(Block block) {
        return block instanceof CropBlock || block instanceof StemBlock || block instanceof NetherWartBlock;
    }

    public static boolean isSoil(BlockState state) {
        return state.getBlock() instanceof FarmlandBlock || state.is(Blocks.SOUL_SAND);
    }

    public static List<Item> availableSeeds(Player player) {
        List<Item> result = new ArrayList<>();
        Inventory inventory = player.getInventory();
        for (Item seed : selectable()) {
            if (Config.seeds.contains(seed) && (inventory.contains(stack -> stack.is(seed)) || player.getOffhandItem().is(seed)))
                result.add(seed);
        }
        return result;
    }

    public static @Nullable Item seedFor(Level level, BlockPos pos, List<Item> seeds) {
        if (!level.getBlockState(pos).isAir() || !isSoil(level.getBlockState(pos.below())))
            return null;
        for (Item seed : seeds) {
            if (seed instanceof BlockItem blockItem && blockItem.getBlock().defaultBlockState().canSurvive(level, pos))
                return seed;
        }
        return null;
    }
}
