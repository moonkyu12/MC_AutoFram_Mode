package org.rust.autofarm;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AttachedStemBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.TorchflowerCropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class Crops {
    static final List<String> DEFAULT_ENABLED = List.of(
            "minecraft:wheat", "minecraft:carrots", "minecraft:potatoes", "minecraft:beetroots",
            "minecraft:nether_wart", "minecraft:cocoa"
    );

    public enum HarvestMethod { BREAK, USE }

    public record Replant(Item seed, BlockPos clickPos, Direction face, Vec3 hit) {}

    private static @Nullable List<Block> selectable;
    private static @Nullable Set<Item> cropItems;

    private Crops() {}

    public static boolean isCropItem(ItemStack stack) {
        if (cropItems == null) {
            Set<Item> set = new HashSet<>(List.of(
                    Items.WHEAT, Items.WHEAT_SEEDS, Items.CARROT, Items.POTATO, Items.POISONOUS_POTATO,
                    Items.BEETROOT, Items.BEETROOT_SEEDS, Items.NETHER_WART, Items.COCOA_BEANS,
                    Items.SWEET_BERRIES, Items.SUGAR_CANE, Items.MELON_SLICE, Items.MELON, Items.PUMPKIN));
            // modded crops
            for (Block block : selectable())
                set.add(block.asItem());
            set.remove(Items.AIR);
            cropItems = set;
        }
        return cropItems.contains(stack.getItem());
    }

    public static List<Block> selectable() {
        if (selectable == null) {
            List<Block> list = new ArrayList<>();
            for (Block block : BuiltInRegistries.BLOCK) {
                // torchflower becomes another block when grown
                if (block instanceof CropBlock && !(block instanceof TorchflowerCropBlock))
                    list.add(block);
            }
            list.sort(Comparator.comparing(b -> BuiltInRegistries.BLOCK.getKey(b).toString()));
            list.addAll(List.of(Blocks.NETHER_WART, Blocks.COCOA, Blocks.SWEET_BERRY_BUSH,
                    Blocks.SUGAR_CANE, Blocks.MELON, Blocks.PUMPKIN));
            selectable = List.copyOf(list);
        }
        return selectable;
    }

    public static boolean isSelectedBlock(BlockState state) {
        return Config.crops.contains(state.getBlock());
    }

    public static boolean isHarvestable(Level level, BlockPos pos, BlockState state, boolean onlyMature) {
        Block block = state.getBlock();
        if (!Config.crops.contains(block))
            return false;

        if (block instanceof CropBlock crop)
            return !onlyMature || crop.isMaxAge(state);
        if (block instanceof NetherWartBlock)
            return !onlyMature || state.getValue(NetherWartBlock.AGE) >= NetherWartBlock.MAX_AGE;
        if (block instanceof CocoaBlock)
            return !onlyMature || state.getValue(CocoaBlock.AGE) >= CocoaBlock.MAX_AGE;
        if (block instanceof SweetBerryBushBlock)
            return state.getValue(SweetBerryBushBlock.AGE) >= (onlyMature ? SweetBerryBushBlock.MAX_AGE : 2);
        // keep the bottom block
        if (block == Blocks.SUGAR_CANE)
            return level.getBlockState(pos.below()).is(Blocks.SUGAR_CANE)
                    && !level.getBlockState(pos.below(2)).is(Blocks.SUGAR_CANE);
        // only ones attached to a stem
        if (block == Blocks.MELON || block == Blocks.PUMPKIN)
            return hasAttachedStem(level, pos);
        return false;
    }

    private static boolean hasAttachedStem(Level level, BlockPos pos) {
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockState neighbour = level.getBlockState(pos.relative(dir));
            if (neighbour.getBlock() instanceof AttachedStemBlock
                    && neighbour.getValue(AttachedStemBlock.FACING) == dir.getOpposite())
                return true;
        }
        return false;
    }

    public static HarvestMethod harvestMethod(BlockState state) {
        return state.getBlock() instanceof SweetBerryBushBlock ? HarvestMethod.USE : HarvestMethod.BREAK;
    }

    public static @Nullable Replant replantFor(Level level, BlockPos pos, BlockState state) {
        Block block = state.getBlock();
        if (block instanceof CropBlock || block instanceof NetherWartBlock) {
            Item seed = seedOf(level, pos, state);
            if (seed == null)
                return null;
            BlockPos soil = pos.below();
            return new Replant(seed, soil, Direction.UP, new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5));
        }
        if (block instanceof CocoaBlock) {
            Item seed = seedOf(level, pos, state);
            if (seed == null)
                return null;
            Direction facing = state.getValue(CocoaBlock.FACING);
            BlockPos log = pos.relative(facing);
            Direction face = facing.getOpposite();
            Vec3 hit = Vec3.atCenterOf(log).add(face.getStepX() * 0.5, 0, face.getStepZ() * 0.5);
            return new Replant(seed, log, face, hit);
        }
        return null;
    }

    private static @Nullable Item seedOf(Level level, BlockPos pos, BlockState state) {
        ItemStack stack = state.getCloneItemStack(level, pos, false);
        return stack.isEmpty() ? null : stack.getItem();
    }
}
