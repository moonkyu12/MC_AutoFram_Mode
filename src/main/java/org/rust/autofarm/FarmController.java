package org.rust.autofarm;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gizmos.GizmoProperties;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.MovementInputUpdateEvent;
import net.minecraftforge.event.TickEvent;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

public final class FarmController {
    private static final int MAX_HIGHLIGHTS = 1024;
    private static final int BLACKLIST_TICKS = 20 * 30;
    private static final int BREAK_TIMEOUT_TICKS = 20 * 5;
    private static final int REPLANT_TIMEOUT_TICKS = 20;
    private static final int STUCK_TICKS = 40;
    private static final int MAX_REPATHS = 3;
    private static final float MAX_TURN_PER_TICK = 35f;
    private static final int DROP_TRACK_TICKS = 40;
    private static final int DROP_WAIT_TICKS = 10;
    private static final int PICKUP_TIMEOUT_TICKS = 20 * 10;
    private static final double PICKUP_RADIUS = 1.0;
    private static final float HUMAN_MAX_TURN = 12f;
    private static final float AIM_TOLERANCE = 4f;
    private static final float HUMAN_WALK_PITCH = 25f;
    private static final int HUMAN_REPLANT_TIMEOUT_TICKS = 60;
    private static final int REPLANT_CHECK_TICKS = 10;
    private static final int RETRY_WINDOW_TICKS = 20 * 10;
    private static final int SKIP_TICKS = 20 * 120;
    private static final int SPRINT_MIN_NODES = 6;

    private static final GizmoStyle CROP_STYLE = GizmoStyle.strokeAndFill(0xFF55FF55, 2.0f, 0x3055FF55);
    private static final GizmoStyle TARGET_STYLE = GizmoStyle.strokeAndFill(0xFFFFB300, 3.0f, 0x60FFB300);
    private static final GizmoStyle PLANT_STYLE = GizmoStyle.strokeAndFill(0xFF4FC3F7, 2.0f, 0x404FC3F7);
    private static final int PATH_COLOR = 0xFF00E5FF;
    private static final int PATH_END_COLOR = 0xFFFF4081;

    public enum Mode { HARVEST, LOOP, PLANT }

    private static boolean active;
    private static Mode mode = Mode.HARVEST;
    private static List<Item> plantSeeds = List.of();
    private static int tick;
    private static int scanCooldown;
    private static int lastScanTick;
    private static List<BlockPos> targets = List.of();
    private static final Map<BlockPos, Integer> blacklist = new HashMap<>();
    private static int count;

    private static @Nullable BlockPos target;
    private static @Nullable List<BlockPos> path;
    private static @Nullable Object pathGoal;
    private static int pathIndex;
    private static double bestWaypointDist;
    private static int ticksWithoutProgress;
    private static int repaths;

    private static @Nullable BlockPos breaking;
    private static Crops.@Nullable Replant breakingReplant;
    private static int breakingTicks;

    private static @Nullable BlockPos replantPos;
    private static Crops.@Nullable Replant replant;
    private static int replantTicks;
    private static int replantTries;
    private static int actionCooldown;

    private record ReplantCheck(BlockPos pos, Crops.Replant plan, int dueTick, int tries) {}
    private static final List<ReplantCheck> replantChecks = new ArrayList<>();

    private record Attempt(int count, int tick) {}
    private static final Map<BlockPos, Attempt> attempts = new HashMap<>();

    private record Harvest(BlockPos pos, int tick) {}
    private static final List<Harvest> recentHarvests = new ArrayList<>();
    private static int lastHarvestTick = Integer.MIN_VALUE;
    private static final Set<Integer> myDrops = new HashSet<>();
    private static final Map<Integer, Integer> itemBlacklist = new HashMap<>();
    private static @Nullable ItemEntity pickup;
    private static int pickupTicks;

    private static BlockPos anchor = BlockPos.ZERO;
    private static boolean waiting;

    private static final Random random = new Random();
    private static double targetReach;
    private static Vec3 aimOffset = Vec3.ZERO;
    private static float walkSpeed = 1f;
    private static float idleYaw, idlePitch;
    private static int nextIdleLook;
    private static boolean aimMiss;
    private static float aimMissYaw, aimMissPitch;
    private static int pauseUntil;
    private static float glanceYaw, glancePitch;
    private static int glanceUntil, nextGlance;
    private static int nextWander;
    private static final Object WANDER = new Object();

    private static @Nullable Vec2 move;
    private static boolean jump;
    private static boolean sprint;

    private FarmController() {}

    public static boolean isActive() {
        return active;
    }

    public static void toggle(Mode newMode) {
        boolean sameMode = active && mode == newMode;
        if (active)
            stop(stoppedMessage());
        if (!sameMode)
            start(newMode);
    }

    private static Component stoppedMessage() {
        return switch (mode) {
            case HARVEST -> Component.translatable("autofarm.message.stopped", count);
            case LOOP -> Component.translatable("autofarm.message.loop_stopped", count);
            case PLANT -> Component.translatable("autofarm.message.plant_stopped", count);
        };
    }

    private static Component statusMessage() {
        return switch (mode) {
            case HARVEST -> Component.translatable("autofarm.message.status", count, targets.size());
            case LOOP -> waiting
                    ? Component.translatable("autofarm.message.loop_waiting", count)
                    : Component.translatable("autofarm.message.loop_status", count, targets.size());
            case PLANT -> Component.translatable("autofarm.message.plant_status", count, targets.size());
        };
    }

    private static boolean human() {
        return Config.motion == Config.MotionStyle.HUMAN;
    }

    private static boolean onlyMature() {
        return (active && mode == Mode.LOOP) || Config.onlyMature;
    }

    private static void start(Mode newMode) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null)
            return;
        if (newMode != Mode.PLANT && Config.crops.get().isEmpty()) {
            player.sendSystemMessage(Component.translatable("autofarm.message.no_crops_selected"));
            return;
        }
        if (newMode == Mode.PLANT) {
            if (Config.seeds.get().isEmpty()) {
                player.sendSystemMessage(Component.translatable("autofarm.message.no_seeds_selected"));
                return;
            }
            if (Planting.availableSeeds(player).isEmpty()) {
                player.sendSystemMessage(Component.translatable("autofarm.message.no_seeds_in_inventory"));
                return;
            }
        }
        mode = newMode;
        active = true;
        count = 0;
        blacklist.clear();
        itemBlacklist.clear();
        attempts.clear();
        recentHarvests.clear();
        myDrops.clear();
        scanCooldown = 0;
        anchor = player.blockPosition();
        waiting = false;
        nextIdleLook = 0;
        glanceUntil = 0;
        nextGlance = tick + 60 + random.nextInt(120);
        nextWander = tick + 200 + random.nextInt(400);
        rollTarget();
        player.sendSystemMessage(switch (mode) {
            case HARVEST -> Component.translatable("autofarm.message.started", Config.range);
            case LOOP -> Component.translatable("autofarm.message.loop_started", Config.range);
            case PLANT -> Component.translatable("autofarm.message.plant_started", Config.plantRange);
        });
    }

    private static void stop(@Nullable Component message) {
        Minecraft mc = Minecraft.getInstance();
        if (breaking != null && mc.gameMode != null)
            mc.gameMode.stopDestroyBlock();
        active = false;
        waiting = false;
        resetTarget();
        pickup = null;
        breaking = null;
        breakingReplant = null;
        clearReplant();
        replantChecks.clear();
        move = null;
        jump = false;
        sprint = false;
        scanCooldown = 0;
        if (mc.player != null) {
            mc.player.setSprinting(false);
            if (message != null)
                mc.player.sendSystemMessage(message);
        }
    }

    private static void resetTarget() {
        target = null;
        path = null;
        pathGoal = null;
        pathIndex = 0;
        repaths = 0;
        ticksWithoutProgress = 0;
    }

    static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        stop(null);
        targets = List.of();
        myDrops.clear();
    }

    static void onClientTick(TickEvent.ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null || mc.gameMode == null) {
            if (active)
                stop(null);
            return;
        }
        if (active && player.isDeadOrDying())
            stop(stoppedMessage());

        while (KeyBindings.TOGGLE.consumeClick())
            toggle(Mode.HARVEST);
        while (KeyBindings.LOOP.consumeClick())
            toggle(Mode.LOOP);
        while (KeyBindings.PLANT.consumeClick())
            toggle(Mode.PLANT);
        while (KeyBindings.OPEN_CONFIG.consumeClick())
            mc.gui.setScreen(new AutoFarmConfigScreen(null));

        tick++;
        blacklist.values().removeIf(until -> until < tick);
        itemBlacklist.values().removeIf(until -> until < tick);
        attempts.values().removeIf(a -> tick - a.tick() > RETRY_WINDOW_TICKS);

        if (--scanCooldown <= 0 && (active || Config.highlight)) {
            rescan(level, player);
            scanCooldown = active && !waiting ? 10 : 20;
        }

        move = null;
        jump = false;
        sprint = false;
        if (active) {
            trackDrops(level);
            if (canRunWith(mc.gui.screen()) && !mc.isPaused()) {
                tickActive(level, player, mc.gameMode);
                if (active && tick % 10 == 0)
                    player.sendOverlayMessage(statusMessage());
            }
            if (active)
                player.setSprinting(sprint && canSprint(player));
        }

        render(level, player);
    }

    private static void rescan(ClientLevel level, LocalPlayer player) {
        List<BlockPos> found;
        if (active && mode == Mode.PLANT) {
            plantSeeds = Planting.availableSeeds(player);
            found = CropScanner.scanPlantable(level, player.blockPosition(), Config.plantRange, Config.verticalRange, plantSeeds);
        } else {
            BlockPos center = active && mode == Mode.LOOP ? anchor : player.blockPosition();
            found = CropScanner.scanHarvestable(level, center, Config.range, Config.verticalRange, onlyMature());
        }
        Vec3 p = player.position();
        found.sort(Comparator.comparingDouble(pos -> pos.distToCenterSqr(p)));
        targets = found;
        lastScanTick = tick;
    }

    static void onMovementInput(MovementInputUpdateEvent event) {
        if (!active || !canRunWith(Minecraft.getInstance().gui.screen()))
            return;
        ClientInput input = event.getInput();
        Vec2 m = move == null ? Vec2.ZERO : move;
        input.moveVector = m;
        input.keyPresses = new Input(m.y > 0.1f, m.y < -0.1f, m.x > 0.1f, m.x < -0.1f, jump, false, sprint);
    }

    private static boolean canRunWith(@Nullable Screen screen) {
        return screen == null || (Config.runInMenus && !(screen instanceof AbstractContainerScreen<?>));
    }

    private static boolean canSprint(LocalPlayer player) {
        Vec2 m = move;
        return m != null && m.y > 0.8f
                && (player.getFoodData().getFoodLevel() > 6 || player.getAbilities().mayfly);
    }

    private static void tickActive(ClientLevel level, LocalPlayer player, MultiPlayerGameMode gameMode) {
        if (actionCooldown > 0)
            actionCooldown--;

        if (breaking != null) {
            tickBreaking(level, player, gameMode);
            return;
        }
        if (replantPos != null) {
            tickReplant(level, player, gameMode);
            return;
        }
        if (checkReplants(level, player)) {
            tickReplant(level, player, gameMode);
            return;
        }
        if (mode == Mode.PLANT) {
            tickPlant(level, player, gameMode);
            return;
        }

        if (target != null && (blacklist.containsKey(target) || !isValidTarget(level, target)))
            resetTarget();
        if (target == null)
            target = pickTarget(level, player);
        if (target != null)
            waiting = false;

        // harvest whatever is in reach first
        double reach = reach(player);
        if (target != null && inReach(player, target, reach)) {
            pickup = null;
            path = null;
            if (aim(player, aimPoint(Vec3.atCenterOf(target))) && actionCooldown == 0)
                harvest(level, player, gameMode, target);
            return;
        }

        // wait for replant checks before moving
        if (!replantChecks.isEmpty())
            return;

        // pick up drops
        ItemEntity item = pickPickup(level, player);
        if (item != null) {
            goToItem(level, player, item);
            return;
        }
        pickup = null;

        // walk to next crop
        if (target == null) {
            if (mode == Mode.LOOP) {
                // loop mode never stops, just wait
                waiting = true;
                if (human())
                    idle(level, player);
                return;
            }
            boolean waitingForDrops = Config.pickup != Config.PickupMode.OFF && tick - lastHarvestTick < DROP_WAIT_TICKS;
            // only stop after a fresh scan
            if (lastScanTick == tick && !waitingForDrops)
                stop(Component.translatable(count == 0 ? "autofarm.message.no_crops" : "autofarm.message.finished", count));
            else
                scanCooldown = 0;
            return;
        }
        if (thinking(Vec3.atCenterOf(target), player))
            return;
        if (!target.equals(pathGoal))
            path = null;
        if (path == null && !computePath(level, player, reach))
            return;
        followPath(player);
    }

    private static boolean thinking(Vec3 point, LocalPlayer player) {
        if (!human() || tick >= pauseUntil)
            return false;
        aim(player, aimPoint(point));
        return true;
    }

    private static void idle(ClientLevel level, LocalPlayer player) {
        if (WANDER.equals(pathGoal) && path != null) {
            followPath(player);
            return;
        }
        if (tick >= nextWander) {
            nextWander = tick + 300 + random.nextInt(500);
            // don't drift too far from start
            Vec3 from = player.position().distanceToSqr(Vec3.atBottomCenterOf(anchor)) > 8 * 8
                    ? Vec3.atBottomCenterOf(anchor) : player.position();
            Vec3 spot = from.add((random.nextDouble() - 0.5) * 6, 0, (random.nextDouble() - 0.5) * 6);
            List<BlockPos> found = PathFinder.findNear(level, player.blockPosition(), spot, 0.8);
            if (found != null && found.size() >= 2) {
                setPath(found, WANDER);
                followPath(player);
                return;
            }
        }
        idleLook(player);
    }

    private static void idleLook(LocalPlayer player) {
        if (tick >= nextIdleLook) {
            idleYaw = player.getYRot() + (random.nextFloat() - 0.5f) * 140f;
            idlePitch = 5f + random.nextFloat() * 35f;
            nextIdleLook = tick + 60 + random.nextInt(100);
        }
        turn(player, idleYaw, idlePitch);
    }

    private static void tickPlant(ClientLevel level, LocalPlayer player, MultiPlayerGameMode gameMode) {
        if (target != null && (blacklist.containsKey(target) || !isValidTarget(level, target)))
            resetTarget();
        if (target == null)
            target = pickTarget(level, player);
        if (target == null) {
            if (lastScanTick == tick)
                stop(Component.translatable(plantSeeds.isEmpty() ? "autofarm.message.out_of_seeds"
                        : count == 0 ? "autofarm.message.no_farmland" : "autofarm.message.plant_finished", count));
            else
                scanCooldown = 0;
            return;
        }

        double reach = reach(player);
        if (!inReach(player, target, reach)) {
            if (thinking(Vec3.atBottomCenterOf(target), player))
                return;
            if (!target.equals(pathGoal))
                path = null;
            if (path == null && !computePath(level, player, reach))
                return;
            followPath(player);
            return;
        }

        path = null;
        Vec3 hit = Vec3.atBottomCenterOf(target); // top of soil
        if (!aim(player, human() ? hit.add(aimOffset.x, 0, aimOffset.z) : hit) || actionCooldown > 0)
            return;
        Item seed = Planting.seedFor(level, target, plantSeeds);
        if (seed == null)
            return;
        InteractionHand hand = prepareSeed(player, gameMode, seed);
        if (hand == null)
            return;

        gameMode.useItemOn(player, hand, new BlockHitResult(hit, Direction.UP, target.below(), false));
        player.swing(hand);
        count++;
        actionDone();
        resetTarget();
    }

    private static boolean isValidTarget(ClientLevel level, BlockPos pos) {
        return mode == Mode.PLANT
                ? Planting.seedFor(level, pos, plantSeeds) != null
                : Crops.isHarvestable(level, pos, level.getBlockState(pos), onlyMature());
    }

    private static double reach(LocalPlayer player) {
        return human() ? targetReach : player.blockInteractionRange();
    }

    private static void rollTarget() {
        targetReach = 1.9 + random.nextDouble() * 0.5;
        aimOffset = new Vec3((random.nextDouble() - 0.5) * 0.4, (random.nextDouble() - 0.5) * 0.3, (random.nextDouble() - 0.5) * 0.4);
        walkSpeed = 0.55f + random.nextFloat() * 0.2f;
        aimMiss = random.nextFloat() < 0.3f;
        aimMissYaw = (random.nextBoolean() ? 1 : -1) * (3f + random.nextFloat() * 6f);
        aimMissPitch = (random.nextFloat() - 0.5f) * 6f;
        pauseUntil = random.nextFloat() < 0.15f ? tick + 5 + random.nextInt(16) : 0;
    }

    private static Vec3 aimPoint(Vec3 center) {
        return human() ? center.add(aimOffset) : center;
    }

    private static void actionDone() {
        actionCooldown = Config.actionDelay;
        if (human()) {
            actionCooldown += 3 + random.nextInt(6);
            if (random.nextFloat() < 0.08f)
                actionCooldown += 15 + random.nextInt(26);
        }
    }

    private static boolean inReach(LocalPlayer player, BlockPos pos, double reach) {
        return new AABB(pos).distanceToSqr(player.getEyePosition()) < (reach - 0.25) * (reach - 0.25);
    }

    private static @Nullable BlockPos pickTarget(ClientLevel level, LocalPlayer player) {
        Vec3 p = player.position();
        BlockPos[] best = new BlockPos[human() ? 3 : 1];
        double[] bestDist = new double[best.length];
        Arrays.fill(bestDist, Double.MAX_VALUE);
        int last = best.length - 1;
        for (BlockPos pos : targets) {
            if (blacklist.containsKey(pos))
                continue;
            double d = pos.distToCenterSqr(p);
            if (d < bestDist[last] && isValidTarget(level, pos)) {
                int i = last;
                for (; i > 0 && d < bestDist[i - 1]; i--) {
                    best[i] = best[i - 1];
                    bestDist[i] = bestDist[i - 1];
                }
                best[i] = pos;
                bestDist[i] = d;
            }
        }
        if (best[0] == null)
            return null;
        rollTarget();
        // not always the closest one
        float r = random.nextFloat();
        int pick = r < 0.7f ? 0 : r < 0.9f ? 1 : 2;
        while (pick >= best.length || best[pick] == null)
            pick--;
        return best[pick];
    }

    private static void giveUp() {
        if (pickup != null) {
            itemBlacklist.put(pickup.getId(), tick + BLACKLIST_TICKS);
            pickup = null;
            path = null;
            pathGoal = null;
            repaths = 0;
            return;
        }
        if (target != null)
            blacklist.put(target, tick + BLACKLIST_TICKS);
        resetTarget();
    }

    private static void harvest(ClientLevel level, LocalPlayer player, MultiPlayerGameMode gameMode, BlockPos pos) {
        if (!attempt(player, pos)) {
            resetTarget();
            return;
        }
        BlockState state = level.getBlockState(pos);
        Crops.Replant plan = Config.replant ? Crops.replantFor(level, pos, state) : null;
        Direction face = Direction.getApproximateNearest(player.getEyePosition().subtract(Vec3.atCenterOf(pos)));

        if (Crops.harvestMethod(state) == Crops.HarvestMethod.USE) {
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), face, pos, false);
            gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
            player.swing(InteractionHand.MAIN_HAND);
            finishHarvest(pos, null);
            return;
        }

        gameMode.startDestroyBlock(pos, face);
        player.swing(InteractionHand.MAIN_HAND);
        if (level.getBlockState(pos).isAir()) {
            finishHarvest(pos, plan);
        } else {
            // takes more than one tick
            breaking = pos;
            breakingReplant = plan;
            breakingTicks = 0;
        }
    }

    private static void tickBreaking(ClientLevel level, LocalPlayer player, MultiPlayerGameMode gameMode) {
        BlockPos pos = breaking;
        if (level.getBlockState(pos).isAir()) {
            breaking = null;
            finishHarvest(pos, breakingReplant);
            return;
        }
        if (++breakingTicks > BREAK_TIMEOUT_TICKS) {
            gameMode.stopDestroyBlock();
            breaking = null;
            if (Config.skipStuck)
                resetTarget();
            else
                giveUp();
            return;
        }
        aim(player, aimPoint(Vec3.atCenterOf(pos)));
        Direction face = Direction.getApproximateNearest(player.getEyePosition().subtract(Vec3.atCenterOf(pos)));
        gameMode.continueDestroyBlock(pos, face);
        player.swing(InteractionHand.MAIN_HAND);
    }

    private static void finishHarvest(BlockPos pos, Crops.@Nullable Replant plan) {
        count++;
        actionDone();
        recentHarvests.add(new Harvest(pos, tick));
        lastHarvestTick = tick;
        resetTarget();
        if (plan != null) {
            replantPos = pos;
            replant = plan;
            replantTicks = 0;
            replantTries = 0;
        }
    }

    private static boolean attempt(LocalPlayer player, BlockPos pos) {
        Attempt last = attempts.get(pos);
        int tries = last != null ? last.count() + 1 : 1;
        if (Config.skipStuck && tries > Config.retries) {
            attempts.remove(pos);
            blacklist.put(pos, tick + SKIP_TICKS);
            player.sendOverlayMessage(Component.translatable("autofarm.message.skipped",
                    Config.retries, pos.getX(), pos.getY(), pos.getZ()));
            return false;
        }
        attempts.put(pos, new Attempt(tries, tick));
        return true;
    }

    private static boolean checkReplants(ClientLevel level, LocalPlayer player) {
        for (Iterator<ReplantCheck> it = replantChecks.iterator(); it.hasNext(); ) {
            ReplantCheck check = it.next();
            if (tick < check.dueTick())
                continue;
            it.remove();
            BlockPos pos = check.pos();
            // planted ok or soil gone
            if (!level.getBlockState(pos).isAir() || level.getBlockState(check.plan().clickPos()).isAir())
                continue;
            if (check.tries() >= Config.retries || !inReach(player, pos, player.blockInteractionRange())) {
                player.sendOverlayMessage(Component.translatable("autofarm.message.replant_failed",
                        pos.getX(), pos.getY(), pos.getZ()));
                continue;
            }
            replantPos = pos;
            replant = check.plan();
            replantTicks = 0;
            replantTries = check.tries();
            return true;
        }
        return false;
    }

    private static void tickReplant(ClientLevel level, LocalPlayer player, MultiPlayerGameMode gameMode) {
        BlockPos pos = replantPos;
        Crops.Replant plan = replant;
        int timeout = human() ? HUMAN_REPLANT_TIMEOUT_TICKS : REPLANT_TIMEOUT_TICKS;
        if (++replantTicks > timeout || level.getBlockState(plan.clickPos()).isAir()) {
            replantChecks.add(new ReplantCheck(pos, plan, tick + REPLANT_CHECK_TICKS, replantTries + 1));
            clearReplant();
            return;
        }
        if (!level.getBlockState(pos).isAir() || actionCooldown > 0)
            return;

        InteractionHand hand = prepareSeed(player, gameMode, plan.seed());
        if (hand == null) {
            if (!player.getInventory().contains(stack -> stack.is(plan.seed()))) {
                player.sendOverlayMessage(Component.translatable("autofarm.message.no_seed",
                        plan.seed().getDefaultInstance().getHoverName()));
                clearReplant();
            }
            return; // moved to hotbar, use next tick
        }

        if (!aim(player, plan.hit()))
            return;
        gameMode.useItemOn(player, hand, new BlockHitResult(plan.hit(), plan.face(), plan.clickPos(), false));
        player.swing(hand);
        replantChecks.add(new ReplantCheck(pos, plan, tick + REPLANT_CHECK_TICKS, replantTries + 1));
        clearReplant();
        actionDone();
    }

    private static void clearReplant() {
        replantPos = null;
        replant = null;
    }

    private static @Nullable InteractionHand prepareSeed(LocalPlayer player, MultiPlayerGameMode gameMode, Item seed) {
        if (player.getMainHandItem().is(seed))
            return InteractionHand.MAIN_HAND;
        if (player.getOffhandItem().is(seed))
            return InteractionHand.OFF_HAND;

        Inventory inventory = player.getInventory();
        for (int i = 0; i < Inventory.getSelectionSize(); i++) {
            if (inventory.getItem(i).is(seed)) {
                inventory.setSelectedSlot(i);
                return InteractionHand.MAIN_HAND;
            }
        }
        // slots 9-35 are the same in inventoryMenu
        for (int i = Inventory.getSelectionSize(); i < Inventory.INVENTORY_SIZE; i++) {
            if (inventory.getItem(i).is(seed)) {
                gameMode.handleContainerInput(player.inventoryMenu.containerId, i, inventory.getSelectedSlot(),
                        ContainerInput.SWAP, player);
                return null;
            }
        }
        return null;
    }

    private static void trackDrops(ClientLevel level) {
        recentHarvests.removeIf(h -> tick - h.tick() > DROP_TRACK_TICKS);
        if (Config.pickup != Config.PickupMode.MINE)
            return;
        for (Harvest h : recentHarvests) {
            int age = tick - h.tick();
            // sugar cane drops above too
            AABB area = new AABB(h.pos()).inflate(1.5).expandTowards(0, 3, 0);
            for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, area)) {
                if (item.tickCount <= age + 5)
                    myDrops.add(item.getId());
            }
        }
    }

    private static @Nullable ItemEntity pickPickup(ClientLevel level, LocalPlayer player) {
        if (Config.pickup == Config.PickupMode.OFF)
            return null;

        Inventory inventory = player.getInventory();
        Vec3 p = player.position();
        ItemEntity best = null;
        double bestDist = Double.MAX_VALUE;
        boolean full = false;
        for (ItemEntity item : candidateItems(level, player)) {
            if (!item.isAlive() || itemBlacklist.containsKey(item.getId()))
                continue;
            if (!canFit(inventory, item.getItem())) {
                full = true;
                continue;
            }
            double d = item.distanceToSqr(p);
            if (d < bestDist) {
                bestDist = d;
                best = item;
            }
        }
        if (best == null && full && tick % 40 == 0)
            player.sendOverlayMessage(Component.translatable("autofarm.message.inventory_full"));
        return best;
    }

    private static List<ItemEntity> candidateItems(ClientLevel level, LocalPlayer player) {
        if (Config.pickup == Config.PickupMode.ALL) {
            AABB area = player.getBoundingBox().inflate(Config.range, Config.verticalRange, Config.range);
            return level.getEntitiesOfClass(ItemEntity.class, area, item -> Crops.isCropItem(item.getItem()));
        }
        List<ItemEntity> items = new ArrayList<>();
        for (Iterator<Integer> it = myDrops.iterator(); it.hasNext(); ) {
            if (level.getEntity(it.next()) instanceof ItemEntity item && item.isAlive())
                items.add(item);
            else
                it.remove();
        }
        return items;
    }

    private static boolean canFit(Inventory inventory, ItemStack stack) {
        return inventory.getFreeSlot() != -1 || inventory.getSlotWithRemainingSpace(stack) != -1;
    }

    private static void goToItem(ClientLevel level, LocalPlayer player, ItemEntity item) {
        if (pickup != item) {
            pickup = item;
            pickupTicks = 0;
        }
        if (++pickupTicks > PICKUP_TIMEOUT_TICKS) {
            giveUp();
            return;
        }

        Vec3 itemPos = item.position(), pos = player.position();
        double dx = itemPos.x - pos.x, dz = itemPos.z - pos.z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        // pickup delay
        if (dist < 0.5 && Math.abs(itemPos.y - pos.y) < 1.5)
            return;

        Integer goal = item.getId();
        if (!goal.equals(pathGoal))
            path = null;
        if (path == null) {
            List<BlockPos> found = PathFinder.findNear(level, player.blockPosition(), itemPos, PICKUP_RADIUS);
            if (found == null) {
                giveUp();
                return;
            }
            if (found.size() < 2) {
                steer(player, dx, dz, dist, (float) Math.min(1.0, Math.max(0.3, dist)), false);
                return;
            }
            setPath(found, goal);
        }
        followPath(player);
    }

    private static boolean computePath(ClientLevel level, LocalPlayer player, double reach) {
        List<BlockPos> found = PathFinder.find(level, player.blockPosition(), target, reach - 0.7);
        if (found == null || found.size() < 2) {
            giveUp();
            return false;
        }
        setPath(found, target);
        return true;
    }

    private static void setPath(List<BlockPos> found, Object goal) {
        if (!goal.equals(pathGoal))
            repaths = 0;
        path = found;
        pathGoal = goal;
        pathIndex = 1;
        bestWaypointDist = Double.MAX_VALUE;
        ticksWithoutProgress = 0;
    }

    private static void followPath(LocalPlayer player) {
        List<BlockPos> p = path;
        Vec3 pos = player.position();
        BlockPos next = p.get(pathIndex);
        double dx = next.getX() + 0.5 - pos.x, dz = next.getZ() + 0.5 - pos.z;
        double dist = Math.sqrt(dx * dx + dz * dz);

        if (dist < 0.35 && Math.abs(pos.y - next.getY()) < 1.0) {
            if (++pathIndex >= p.size()) {
                path = null;
                return;
            }
            bestWaypointDist = Double.MAX_VALUE;
            ticksWithoutProgress = 0;
            next = p.get(pathIndex);
            dx = next.getX() + 0.5 - pos.x;
            dz = next.getZ() + 0.5 - pos.z;
            dist = Math.sqrt(dx * dx + dz * dz);
        }

        // stuck -> repath, then give up
        if (dist < bestWaypointDist - 0.05) {
            bestWaypointDist = dist;
            ticksWithoutProgress = 0;
        } else if (++ticksWithoutProgress > STUCK_TICKS || dist > 3 || pos.y < next.getY() - 2.5) {
            if (++repaths > MAX_REPATHS) {
                giveUp();
            } else {
                path = null;
            }
            return;
        }

        sprint = !human() && Config.sprint && p.size() - pathIndex > SPRINT_MIN_NODES;
        // slow down at the end
        float speed = pathIndex == p.size() - 1 && dist < 1.0 ? (float) Math.max(0.3, dist) : 1f;
        steer(player, dx, dz, dist, speed, next.getY() > pos.y + 0.5);
    }

    private static void steer(LocalPlayer player, double dx, double dz, double dist, float speed, boolean stepUp) {
        if (dist < 1.0E-4)
            return;
        float desiredYaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90f;
        float yaw;
        if (human()) {
            // look around a bit while walking
            if (tick >= nextGlance) {
                nextGlance = tick + 80 + random.nextInt(160);
                glanceYaw = desiredYaw + (random.nextBoolean() ? 1 : -1) * (30f + random.nextFloat() * 50f);
                glancePitch = -5f + random.nextFloat() * 40f;
                glanceUntil = tick + 8 + random.nextInt(13);
            }
            boolean glancing = tick < glanceUntil;
            turn(player, glancing ? glanceYaw : desiredYaw, glancing ? glancePitch : HUMAN_WALK_PITCH);
            yaw = player.getYRot();
            float facing = Mth.cos(Mth.wrapDegrees(desiredYaw - yaw) * Mth.DEG_TO_RAD);
            speed *= walkSpeed * (glancing ? 0.8f : Math.max(0.2f, facing));
        } else {
            yaw = player.getYRot() + Mth.clamp(Mth.wrapDegrees(desiredYaw - player.getYRot()), -MAX_TURN_PER_TICK, MAX_TURN_PER_TICK);
            player.setYRot(yaw);
            player.setXRot(Mth.approach(player.getXRot(), 20f, 10f));
        }

        // world dir -> local input
        double wx = dx / dist, wz = dz / dist;
        float sin = Mth.sin(yaw * Mth.DEG_TO_RAD), cos = Mth.cos(yaw * Mth.DEG_TO_RAD);
        float strafe = (float) (wx * cos + wz * sin);
        float forward = (float) (-wx * sin + wz * cos);
        move = new Vec2(strafe * speed, forward * speed);

        // jumping on farmland tramples it
        jump = stepUp && player.onGround() && dist < 1.6;
    }

    private static boolean aim(LocalPlayer player, Vec3 point) {
        if (!human()) {
            player.lookAt(EntityAnchorArgument.Anchor.EYES, point);
            return true;
        }
        Vec3 eye = player.getEyePosition();
        double dx = point.x - eye.x, dy = point.y - eye.y, dz = point.z - eye.z;
        float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90f;
        float pitch = (float) -(Mth.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * Mth.RAD_TO_DEG);
        if (aimMiss) {
            turn(player, yaw + aimMissYaw, pitch + aimMissPitch);
            if (lookingAt(player, yaw + aimMissYaw, pitch + aimMissPitch))
                aimMiss = false;
            return false;
        }
        turn(player, yaw, pitch);
        return lookingAt(player, yaw, pitch);
    }

    private static boolean lookingAt(LocalPlayer player, float yaw, float pitch) {
        return Math.abs(Mth.wrapDegrees(yaw - player.getYRot())) < AIM_TOLERANCE
                && Math.abs(pitch - player.getXRot()) < AIM_TOLERANCE;
    }

    private static void turn(LocalPlayer player, float yaw, float pitch) {
        player.setYRot(player.getYRot() + ease(Mth.wrapDegrees(yaw - player.getYRot())) + tremble());
        player.setXRot(Mth.clamp(player.getXRot() + ease(pitch - player.getXRot()) + tremble(), -90f, 90f));
    }

    private static float ease(float diff) {
        float step = Mth.clamp(diff * 0.3f, -HUMAN_MAX_TURN, HUMAN_MAX_TURN);
        if (Math.abs(step) < 1f)
            step = Math.signum(diff) * Math.min(1f, Math.abs(diff));
        return step;
    }

    private static float tremble() {
        return (random.nextFloat() - 0.5f) * 0.5f;
    }

    private static void render(ClientLevel level, LocalPlayer player) {
        boolean onTop = Config.highlightThroughWalls;
        boolean planting = active && mode == Mode.PLANT;

        if (Config.highlight) {
            int shown = 0;
            for (BlockPos pos : targets) {
                if (pos.equals(target) || blacklist.containsKey(pos))
                    continue;
                if (shown++ >= MAX_HIGHLIGHTS)
                    break;
                top(planting ? Gizmos.cuboid(plantBoxOf(pos), PLANT_STYLE) : Gizmos.cuboid(boxOf(level, pos), CROP_STYLE), onTop);
            }

            BlockPos current = breaking != null ? breaking : target;
            if (active && current != null)
                top(Gizmos.cuboid((planting ? plantBoxOf(current) : boxOf(level, current)).inflate(0.03), TARGET_STYLE), onTop);
            ItemEntity item = pickup;
            if (active && item != null && item.isAlive())
                top(Gizmos.cuboid(item.getBoundingBox().inflate(0.1), TARGET_STYLE), onTop);
        }

        List<BlockPos> p = path;
        if (active && Config.showPath && p != null) {
            Vec3 from = player.position().add(0, 0.1, 0);
            for (int i = pathIndex; i < p.size(); i++) {
                Vec3 to = Vec3.atBottomCenterOf(p.get(i)).add(0, 0.1, 0);
                top(Gizmos.line(from, to, PATH_COLOR, 3.0f), onTop);
                from = to;
            }
            top(Gizmos.point(from, PATH_END_COLOR, 6.0f), onTop);
        }
    }

    private static void top(GizmoProperties properties, boolean onTop) {
        if (onTop)
            properties.setAlwaysOnTop();
    }

    private static AABB boxOf(ClientLevel level, BlockPos pos) {
        VoxelShape shape = level.getBlockState(pos).getShape(level, pos);
        return shape.isEmpty() ? new AABB(pos) : shape.bounds().move(pos);
    }

    private static AABB plantBoxOf(BlockPos pos) {
        return new AABB(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 0.15, pos.getZ() + 1);
    }
}
