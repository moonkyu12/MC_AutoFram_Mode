package org.rust.autofarm;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

public final class CropScanner {
    private CropScanner() {}

    public static List<BlockPos> scanHarvestable(Level level, BlockPos center, int range, int verticalRange, boolean onlyMature) {
        if (Config.crops.get().isEmpty())
            return new ArrayList<>();
        return scan(level, center, range, verticalRange, Crops::isSelectedBlock,
                (pos, state) -> Crops.isHarvestable(level, pos, state, onlyMature));
    }

    public static List<BlockPos> scanPlantable(Level level, BlockPos center, int range, int verticalRange, List<Item> seeds) {
        List<BlockPos> result = new ArrayList<>();
        if (seeds.isEmpty())
            return result;
        for (BlockPos soil : scan(level, center, range, verticalRange, Planting::isSoil,
                (pos, state) -> Planting.seedFor(level, pos.above(), seeds) != null))
            result.add(soil.above());
        return result;
    }

    private static List<BlockPos> scan(Level level, BlockPos center, int range, int verticalRange,
                                       Predicate<BlockState> candidate, BiPredicate<BlockPos, BlockState> accept) {
        List<BlockPos> result = new ArrayList<>();
        long rangeSq = (long) range * range;
        int minY = Math.max(level.getMinY(), center.getY() - verticalRange);
        int maxY = Math.min(level.getMaxY(), center.getY() + verticalRange);
        int minCx = SectionPos.blockToSectionCoord(center.getX() - range);
        int maxCx = SectionPos.blockToSectionCoord(center.getX() + range);
        int minCz = SectionPos.blockToSectionCoord(center.getZ() - range);
        int maxCz = SectionPos.blockToSectionCoord(center.getZ() + range);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                if (!(level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false) instanceof LevelChunk chunk))
                    continue;

                for (int sy = SectionPos.blockToSectionCoord(minY); sy <= SectionPos.blockToSectionCoord(maxY); sy++) {
                    LevelChunkSection section = chunk.getSection(chunk.getSectionIndexFromSectionY(sy));
                    if (section.hasOnlyAir() || !section.maybeHas(candidate))
                        continue;

                    int baseX = cx << 4, baseY = sy << 4, baseZ = cz << 4;
                    for (int ly = 0; ly < 16; ly++) {
                        int y = baseY + ly;
                        if (y < minY || y > maxY)
                            continue;
                        for (int lz = 0; lz < 16; lz++) {
                            for (int lx = 0; lx < 16; lx++) {
                                BlockState state = section.getBlockState(lx, ly, lz);
                                if (!candidate.test(state))
                                    continue;
                                long dx = baseX + lx - center.getX(), dz = baseZ + lz - center.getZ();
                                if (dx * dx + dz * dz > rangeSq)
                                    continue;
                                pos.set(baseX + lx, y, baseZ + lz);
                                if (accept.test(pos, state))
                                    result.add(pos.immutable());
                            }
                        }
                    }
                }
            }
        }
        return result;
    }
}
