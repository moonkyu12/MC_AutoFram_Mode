package org.rust.autofarm;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.Predicate;

public final class PathFinder {
    private static final int MAX_EXPANSIONS = 40_000;
    private static final int MAX_DROP = 3;
    private static final double EYE_HEIGHT = 1.62;
    private static final int[][] DIRS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    private final Level level;
    private final Map<Long, Boolean> passableCache = new HashMap<>();
    private final Map<Long, Boolean> floorCache = new HashMap<>();

    private PathFinder(Level level) {
        this.level = level;
    }

    private static final class Node implements Comparable<Node> {
        final BlockPos pos;
        @Nullable Node parent;
        double g, f;
        boolean closed;

        Node(BlockPos pos) {
            this.pos = pos;
        }

        @Override
        public int compareTo(Node o) {
            return Double.compare(f, o.f);
        }
    }

    public static @Nullable List<BlockPos> find(Level level, BlockPos start, BlockPos target, double reach) {
        return new PathFinder(level).search(start, Vec3.atCenterOf(target), reach, feet -> canReachFrom(feet, target, reach));
    }

    public static @Nullable List<BlockPos> findNear(Level level, BlockPos start, Vec3 point, double radius) {
        return new PathFinder(level).search(start, point, radius, feet -> {
            double dx = feet.getX() + 0.5 - point.x, dz = feet.getZ() + 0.5 - point.z;
            return dx * dx + dz * dz <= radius * radius && point.y >= feet.getY() - 1 && point.y <= feet.getY() + 1.5;
        });
    }

    public static boolean canReachFrom(BlockPos feet, BlockPos target, double reach) {
        Vec3 eye = new Vec3(feet.getX() + 0.5, feet.getY() + EYE_HEIGHT, feet.getZ() + 0.5);
        return new AABB(target).distanceToSqr(eye) < reach * reach;
    }

    private @Nullable List<BlockPos> search(BlockPos start, Vec3 target, double slack, Predicate<BlockPos> goal) {
        start = findStart(start);
        Map<Long, Node> nodes = new HashMap<>();
        PriorityQueue<Node> open = new PriorityQueue<>();

        Node startNode = new Node(start);
        startNode.f = heuristic(start, target, slack);
        nodes.put(start.asLong(), startNode);
        open.add(startNode);

        int expansions = 0;
        while (!open.isEmpty() && expansions++ < MAX_EXPANSIONS) {
            Node current = open.poll();
            if (current.closed)
                continue;
            current.closed = true;

            if (goal.test(current.pos))
                return reconstruct(current);

            for (Move move : neighbours(current.pos)) {
                Node next = nodes.computeIfAbsent(move.pos.asLong(), k -> new Node(move.pos));
                if (next.closed)
                    continue;
                double g = current.g + move.cost;
                if (next.parent == null && next != startNode || g < next.g) {
                    next.parent = current;
                    next.g = g;
                    next.f = g + heuristic(next.pos, target, slack);
                    // stale entries get skipped via closed
                    open.add(next);
                }
            }
        }
        return null;
    }

    private BlockPos findStart(BlockPos feet) {
        if (isStandable(feet))
            return feet;
        if (isStandable(feet.above()))
            return feet.above();
        if (isStandable(feet.below()))
            return feet.below();
        return feet;
    }

    private static double heuristic(BlockPos pos, Vec3 target, double slack) {
        double dx = pos.getX() + 0.5 - target.x, dy = pos.getY() - target.y, dz = pos.getZ() + 0.5 - target.z;
        return Math.max(0, Math.sqrt(dx * dx + dy * dy + dz * dz) - slack);
    }

    private static List<BlockPos> reconstruct(Node node) {
        List<BlockPos> path = new ArrayList<>();
        for (Node n = node; n != null; n = n.parent)
            path.add(n.pos);
        Collections.reverse(path);
        return path;
    }

    private record Move(BlockPos pos, double cost) {}

    private List<Move> neighbours(BlockPos pos) {
        List<Move> moves = new ArrayList<>(12);
        for (int[] d : DIRS) {
            int dx = d[0], dz = d[1];
            boolean diagonal = dx != 0 && dz != 0;
            BlockPos side = pos.offset(dx, 0, dz);

            if (diagonal) {
                // no corner cutting
                if (!isPassableColumn(pos.offset(dx, 0, 0)) || !isPassableColumn(pos.offset(0, 0, dz)))
                    continue;
                if (isStandable(side))
                    moves.add(new Move(side, Math.sqrt(2)));
                continue;
            }

            if (isStandable(side)) {
                moves.add(new Move(side, 1));
                continue;
            }

            // jump up
            BlockPos up = side.above();
            if (isStandable(up) && isPassable(pos.above(2))) {
                moves.add(new Move(up, 2.0));
                continue;
            }

            // drop down, but not onto farmland (tramples)
            if (isPassableColumn(side)) {
                for (int drop = 1; drop <= MAX_DROP; drop++) {
                    BlockPos down = side.below(drop);
                    if (isStandable(down)) {
                        if (!level.getBlockState(down.below()).is(Blocks.FARMLAND))
                            moves.add(new Move(down, 1 + drop * 0.5));
                        break;
                    }
                    if (!isPassable(down))
                        break;
                }
            }
        }
        return moves;
    }

    private boolean isPassableColumn(BlockPos feet) {
        return isPassable(feet) && isPassable(feet.above());
    }

    private boolean isStandable(BlockPos feet) {
        return isPassableColumn(feet) && isFloor(feet.below());
    }

    private boolean isPassable(BlockPos pos) {
        return passableCache.computeIfAbsent(pos.asLong(), k -> {
            BlockState state = level.getBlockState(pos);
            if (!state.getFluidState().isEmpty())
                return false;
            if (state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.COBWEB) || state.is(Blocks.POWDER_SNOW)
                    || state.is(BlockTags.FIRE) || state.is(Blocks.CACTUS))
                return false;
            return state.getCollisionShape(level, pos).isEmpty();
        });
    }

    private boolean isFloor(BlockPos pos) {
        return floorCache.computeIfAbsent(pos.asLong(), k -> {
            BlockState state = level.getBlockState(pos);
            if (state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.CACTUS) || state.is(Blocks.CAMPFIRE)
                    || state.is(Blocks.SOUL_CAMPFIRE))
                return false;
            VoxelShape shape = state.getCollisionShape(level, pos);
            return !shape.isEmpty() && shape.max(Direction.Axis.Y) <= 1.0;
        });
    }
}
