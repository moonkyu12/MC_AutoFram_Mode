package org.rust.autofarm;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;

import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

public class AutoFarmConfigScreen extends Screen {
    private static final int WIDGET_WIDTH = 150;
    private static final int ROW = 22;

    private final @Nullable Screen parent;

    public AutoFarmConfigScreen(@Nullable Screen parent) {
        super(Component.translatable("autofarm.config.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int left = this.width / 2 - WIDGET_WIDTH - 5;
        int right = this.width / 2 + 5;
        int y = 32;

        addRenderableWidget(new IntSlider(left, y, "autofarm.config.range", 4, 128, Config.range, v -> Config.range = v));
        addRenderableWidget(new IntSlider(right, y, "autofarm.config.vertical_range", 1, 64, Config.verticalRange, v -> Config.verticalRange = v));
        y += ROW;

        addToggle(left, y, "only_mature", Config.onlyMature, v -> Config.onlyMature = v);
        addToggle(right, y, "replant", Config.replant, v -> Config.replant = v);
        y += ROW;

        addToggle(left, y, "highlight", Config.highlight, v -> Config.highlight = v);
        addToggle(right, y, "through_walls", Config.highlightThroughWalls, v -> Config.highlightThroughWalls = v);
        y += ROW;

        addToggle(left, y, "show_path", Config.showPath, v -> Config.showPath = v);
        addRenderableWidget(new IntSlider(right, y, "autofarm.config.action_delay", 0, 20, Config.actionDelay, v -> Config.actionDelay = v));
        y += ROW;

        CycleButton<Config.PickupMode> pickup = CycleButton
                .builder((Config.PickupMode mode) -> Component.translatable("autofarm.config.pickup." + mode.name().toLowerCase(Locale.ROOT)),
                        Config.pickup)
                .withValues(Config.PickupMode.values())
                .create(left, y, WIDGET_WIDTH, 20, Component.translatable("autofarm.config.pickup"),
                        (b, mode) -> Config.pickup = mode);
        pickup.setTooltip(Tooltip.create(Component.translatable("autofarm.config.pickup.tooltip")));
        addRenderableWidget(pickup);
        addRenderableWidget(new IntSlider(right, y, "autofarm.config.plant_range", 1, 128, Config.plantRange, v -> Config.plantRange = v));
        y += ROW;

        CycleButton<Config.MotionStyle> motion = CycleButton
                .builder((Config.MotionStyle style) -> Component.translatable("autofarm.config.motion." + style.name().toLowerCase(Locale.ROOT)),
                        Config.motion)
                .withValues(Config.MotionStyle.values())
                .create(left, y, WIDGET_WIDTH, 20, Component.translatable("autofarm.config.motion"),
                        (b, style) -> Config.motion = style);
        motion.setTooltip(Tooltip.create(Component.translatable("autofarm.config.motion.tooltip")));
        addRenderableWidget(motion);
        addToggle(right, y, "sprint", Config.sprint, v -> Config.sprint = v);
        y += ROW;

        addToggle(left, y, "skip_stuck", Config.skipStuck, v -> Config.skipStuck = v);
        addRenderableWidget(new IntSlider(right, y, "autofarm.config.retries", 1, 10, Config.retries, v -> Config.retries = v));
        y += ROW;

        addToggle(left, y, "run_in_menus", Config.runInMenus, v -> Config.runInMenus = v);
        y += ROW + 6;

        addRenderableWidget(Button.builder(
                        Component.translatable("autofarm.config.crops", Config.crops.get().size()),
                        b -> this.minecraft.gui.setScreen(SelectScreen.crops(this)))
                .bounds(left, y, WIDGET_WIDTH, 20)
                .tooltip(Tooltip.create(Component.translatable("autofarm.config.crops.tooltip")))
                .build());
        addRenderableWidget(Button.builder(
                        Component.translatable("autofarm.config.seeds", Config.seeds.get().size()),
                        b -> this.minecraft.gui.setScreen(SelectScreen.seeds(this)))
                .bounds(right, y, WIDGET_WIDTH, 20)
                .tooltip(Tooltip.create(Component.translatable("autofarm.config.seeds.tooltip")))
                .build());

        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(this.width / 2 - 100, this.height - 28, 200, 20)
                .build());
    }

    private void addToggle(int x, int y, String key, boolean value, Consumer<Boolean> setter) {
        CycleButton<Boolean> button = CycleButton.onOffBuilder(value)
                .create(x, y, WIDGET_WIDTH, 20, Component.translatable("autofarm.config." + key), (b, v) -> setter.accept(v));
        button.setTooltip(Tooltip.create(Component.translatable("autofarm.config." + key + ".tooltip")));
        addRenderableWidget(button);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        graphics.centeredText(this.font, this.title, this.width / 2, 15, 0xFFFFFFFF);
        Component hint = Component.translatable("autofarm.config.hint",
                KeyBindings.TOGGLE.getTranslatedKeyMessage(), KeyBindings.LOOP.getTranslatedKeyMessage(),
                KeyBindings.PLANT.getTranslatedKeyMessage(),
                KeyBindings.OPEN_CONFIG.getTranslatedKeyMessage());
        graphics.centeredText(this.font, hint, this.width / 2, this.height - 44, 0xFFA0A0A0);
    }

    @Override
    public void onClose() {
        Config.save();
        this.minecraft.gui.setScreen(this.parent);
    }

    private static class IntSlider extends AbstractSliderButton {
        private final String key;
        private final int min, max;
        private final IntConsumer setter;

        IntSlider(int x, int y, String key, int min, int max, int value, IntConsumer setter) {
            super(x, y, WIDGET_WIDTH, 20, Component.empty(), (double) (value - min) / (max - min));
            this.key = key;
            this.min = min;
            this.max = max;
            this.setter = setter;
            setTooltip(Tooltip.create(Component.translatable(key + ".tooltip")));
            updateMessage();
        }

        private int intValue() {
            return Mth.clamp((int) Math.round(min + this.value * (max - min)), min, max);
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.translatable(key, intValue()));
        }

        @Override
        protected void applyValue() {
            setter.accept(intValue());
        }
    }
}
