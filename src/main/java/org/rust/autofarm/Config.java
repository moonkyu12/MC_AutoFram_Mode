package org.rust.autofarm;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.common.ForgeConfigSpec;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class Config {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    private static final ForgeConfigSpec.IntValue RANGE = BUILDER
            .comment("Horizontal radius (in blocks) around the player to look for crops")
            .defineInRange("range", 100, 4, 128);

    private static final ForgeConfigSpec.IntValue VERTICAL_RANGE = BUILDER
            .comment("Vertical distance (in blocks, up and down) around the player to look for crops / farmland")
            .defineInRange("verticalRange", 8, 1, 64);

    private static final ForgeConfigSpec.BooleanValue ONLY_MATURE = BUILDER
            .comment("Only harvest fully grown crops")
            .define("onlyMature", true);

    private static final ForgeConfigSpec.BooleanValue REPLANT = BUILDER
            .comment("Replant the seed after harvesting (wheat, carrots, potatoes, beetroot, nether wart, cocoa, ...)")
            .define("replant", true);

    private static final ForgeConfigSpec.BooleanValue HIGHLIGHT = BUILDER
            .comment("Highlight crops that will be harvested / farmland that will be planted")
            .define("highlight", true);

    private static final ForgeConfigSpec.BooleanValue HIGHLIGHT_THROUGH_WALLS = BUILDER
            .comment("Render highlights and path through blocks")
            .define("highlightThroughWalls", true);

    private static final ForgeConfigSpec.BooleanValue SHOW_PATH = BUILDER
            .comment("Render the path the player is walking along")
            .define("showPath", true);

    private static final ForgeConfigSpec.IntValue ACTION_DELAY = BUILDER
            .comment("Ticks to wait between two harvest / plant actions")
            .defineInRange("actionDelay", 2, 0, 20);

    private static final ForgeConfigSpec.EnumValue<PickupMode> PICKUP = BUILDER
            .comment("Which dropped items to walk to and pick up: OFF, MINE (drops of crops this mod harvested) or ALL (any crop item in range)")
            .defineEnum("pickup", PickupMode.MINE);

    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> CROPS = BUILDER
            .comment("Block ids of crops to harvest")
            .defineListAllowEmpty("crops", Crops.DEFAULT_ENABLED, Config::isId);

    private static final ForgeConfigSpec.IntValue PLANT_RANGE = BUILDER
            .comment("Horizontal radius (in blocks) around the player in which empty farmland is planted (Home key)")
            .defineInRange("plantRange", 16, 1, 128);

    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> PLANT_SEEDS = BUILDER
            .comment("Item ids of seeds to plant on empty farmland / soul sand")
            .defineListAllowEmpty("plantSeeds", Planting.DEFAULT_SEEDS, Config::isId);

    private static final ForgeConfigSpec.EnumValue<MotionStyle> MOTION = BUILDER
            .comment("How the player moves while farming: FAST (machine-like: instant aim, full speed, harvests from max reach)",
                    "or HUMAN (walks slowly up to 1-2 blocks from the crop, turns the camera smoothly, small random pauses)")
            .defineEnum("motion", MotionStyle.FAST);

    private static final ForgeConfigSpec.BooleanValue SPRINT = BUILDER
            .comment("FAST motion only: sprint while the next target is still far away")
            .define("sprint", true);

    private static final ForgeConfigSpec.BooleanValue RUN_IN_MENUS = BUILDER
            .comment("Keep farming while chat, the Esc menu or another menu is open (not inventories / chests).",
                    "In singleplayer the Esc menu pauses the game unless the world is opened to LAN")
            .define("runInMenus", true);

    private static final ForgeConfigSpec.BooleanValue SKIP_STUCK = BUILDER
            .comment("Leave a crop alone for a while (and move on) once harvesting it failed 'retries' times in a row")
            .define("skipStuck", true);

    private static final ForgeConfigSpec.IntValue RETRIES = BUILDER
            .comment("How many times to try harvesting a stuck crop (with skipStuck) and replanting a spot that stays empty")
            .defineInRange("retries", 3, 1, 10);

    static final ForgeConfigSpec SPEC = BUILDER.build();

    public static int range;
    public static int verticalRange;
    public static boolean onlyMature;
    public static boolean replant;
    public static boolean highlight;
    public static boolean highlightThroughWalls;
    public static boolean showPath;
    public static int actionDelay;
    public static PickupMode pickup = PickupMode.MINE;
    public static int plantRange;
    public static MotionStyle motion = MotionStyle.FAST;
    public static boolean sprint;
    public static boolean runInMenus;
    public static boolean skipStuck;
    public static int retries;
    public static final Selection<Block> crops = new Selection<>(BuiltInRegistries.BLOCK);
    public static final Selection<Item> seeds = new Selection<>(BuiltInRegistries.ITEM);

    public enum PickupMode { OFF, MINE, ALL }

    public enum MotionStyle { FAST, HUMAN }

    public static final class Selection<T> {
        private final Registry<T> registry;
        private Set<String> ids = new HashSet<>();
        private @Nullable Set<T> resolved;

        private Selection(Registry<T> registry) {
            this.registry = registry;
        }

        public Set<T> get() {
            if (resolved == null) {
                Set<T> set = new HashSet<>();
                for (String id : ids)
                    registry.getOptional(Identifier.tryParse(id)).ifPresent(set::add);
                resolved = set;
            }
            return resolved;
        }

        public boolean contains(T entry) {
            return get().contains(entry);
        }

        public void set(T entry, boolean enabled) {
            Identifier key = registry.getKey(entry);
            if (key == null)
                return;
            if (enabled)
                ids.add(key.toString());
            else
                ids.remove(key.toString());
            resolved = null;
        }

        public void setAll(Collection<T> entries) {
            ids = new HashSet<>();
            resolved = null;
            for (T entry : entries)
                set(entry, true);
        }

        private void load(List<? extends String> list) {
            ids = new HashSet<>(list);
            resolved = null;
        }

        private List<String> sortedIds() {
            List<String> list = new ArrayList<>(ids);
            list.sort(null);
            return list;
        }
    }

    private Config() {}

    private static boolean isId(final Object obj) {
        return obj instanceof final String id && Identifier.tryParse(id) != null;
    }

    static void load() {
        range = RANGE.get();
        verticalRange = VERTICAL_RANGE.get();
        onlyMature = ONLY_MATURE.get();
        replant = REPLANT.get();
        highlight = HIGHLIGHT.get();
        highlightThroughWalls = HIGHLIGHT_THROUGH_WALLS.get();
        showPath = SHOW_PATH.get();
        actionDelay = ACTION_DELAY.get();
        pickup = PICKUP.get();
        crops.load(CROPS.get());
        plantRange = PLANT_RANGE.get();
        seeds.load(PLANT_SEEDS.get());
        motion = MOTION.get();
        sprint = SPRINT.get();
        runInMenus = RUN_IN_MENUS.get();
        skipStuck = SKIP_STUCK.get();
        retries = RETRIES.get();
    }

    public static void save() {
        RANGE.set(range);
        VERTICAL_RANGE.set(verticalRange);
        ONLY_MATURE.set(onlyMature);
        REPLANT.set(replant);
        HIGHLIGHT.set(highlight);
        HIGHLIGHT_THROUGH_WALLS.set(highlightThroughWalls);
        SHOW_PATH.set(showPath);
        ACTION_DELAY.set(actionDelay);
        PICKUP.set(pickup);
        CROPS.set(crops.sortedIds());
        PLANT_RANGE.set(plantRange);
        PLANT_SEEDS.set(seeds.sortedIds());
        MOTION.set(motion);
        SPRINT.set(sprint);
        RUN_IN_MENUS.set(runInMenus);
        SKIP_STUCK.set(skipStuck);
        RETRIES.set(retries);
        SPEC.save();
    }
}
