package org.rust.autofarm;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public class SelectScreen<T> extends Screen {
    private static final int BUTTON_WIDTH = 150;
    private static final int ROW = 24;
    private static final int ICON = 18;

    private final Screen parent;
    private final List<T> options;
    private final Config.Selection<T> selection;
    private final List<T> defaults;
    private final Function<T, Component> name;
    private final Function<T, ItemStack> icon;
    private final List<Entry> entries = new ArrayList<>();
    private int page;

    private record Entry(ItemStack icon, int x, int y) {}

    private SelectScreen(Screen parent, Component title, List<T> options, Config.Selection<T> selection,
                         List<T> defaults, Function<T, Component> name, Function<T, ItemStack> icon) {
        super(title);
        this.parent = parent;
        this.options = options;
        this.selection = selection;
        this.defaults = defaults;
        this.name = name;
        this.icon = icon;
    }

    public static SelectScreen<Block> crops(Screen parent) {
        List<Block> defaults = Crops.selectable().stream()
                .filter(block -> Crops.DEFAULT_ENABLED.contains(BuiltInRegistries.BLOCK.getKey(block).toString()))
                .toList();
        return new SelectScreen<>(parent, Component.translatable("autofarm.crops.title"), Crops.selectable(),
                Config.crops, defaults, Block::getName, SelectScreen::blockIcon);
    }

    public static SelectScreen<Item> seeds(Screen parent) {
        List<Item> defaults = Planting.selectable().stream()
                .filter(item -> Planting.DEFAULT_SEEDS.contains(BuiltInRegistries.ITEM.getKey(item).toString()))
                .toList();
        return new SelectScreen<>(parent, Component.translatable("autofarm.seeds.title"), Planting.selectable(),
                Config.seeds, defaults, item -> item.getDefaultInstance().getHoverName(), Item::getDefaultInstance);
    }

    private static ItemStack blockIcon(Block block) {
        ItemStack stack = block.asItem().getDefaultInstance();
        return stack.isEmpty() ? new ItemStack(Items.WHEAT_SEEDS) : stack;
    }

    private int perPage() {
        int rows = Math.max(1, (this.height - 40 - 60) / ROW);
        return rows * 2;
    }

    @Override
    protected void init() {
        entries.clear();
        int perPage = perPage();
        int pages = Math.max(1, (options.size() + perPage - 1) / perPage);
        page = Math.min(page, pages - 1);

        int columnWidth = BUTTON_WIDTH + ICON + 10;
        int left = this.width / 2 - columnWidth;
        for (int i = page * perPage, n = 0; i < options.size() && n < perPage; i++, n++) {
            T option = options.get(i);
            int x = left + (n % 2) * columnWidth + ICON;
            int y = 36 + (n / 2) * ROW;
            entries.add(new Entry(icon.apply(option), x - ICON, y + 2));
            addRenderableWidget(CycleButton.onOffBuilder(selection.contains(option))
                    .create(x, y, BUTTON_WIDTH, 20, name.apply(option), (b, enabled) -> selection.set(option, enabled)));
        }

        int bottom = this.height - 28;
        addRenderableWidget(Button.builder(Component.translatable("autofarm.select.all"), b -> {
            selection.setAll(options);
            rebuildWidgets();
        }).bounds(this.width / 2 - 154, bottom - 24, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("autofarm.select.none"), b -> {
            selection.setAll(List.of());
            rebuildWidgets();
        }).bounds(this.width / 2 - 50, bottom - 24, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("autofarm.select.default"), b -> {
            selection.setAll(defaults);
            rebuildWidgets();
        }).bounds(this.width / 2 + 54, bottom - 24, 100, 20).build());

        Button prev = addRenderableWidget(Button.builder(Component.literal("<"), b -> {
            page--;
            rebuildWidgets();
        }).bounds(this.width / 2 - 154, bottom, 40, 20).build());
        prev.active = page > 0;
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(this.width / 2 - 100, bottom, 200, 20).build());
        Button next = addRenderableWidget(Button.builder(Component.literal(">"), b -> {
            page++;
            rebuildWidgets();
        }).bounds(this.width / 2 + 114, bottom, 40, 20).build());
        next.active = page < pages - 1;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        graphics.centeredText(this.font, this.title, this.width / 2, 15, 0xFFFFFFFF);
        for (Entry entry : entries)
            graphics.item(entry.icon(), entry.x(), entry.y());
    }

    @Override
    public void onClose() {
        Config.save();
        this.minecraft.gui.setScreen(this.parent);
    }
}
