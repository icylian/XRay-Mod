package pro.mikey.xray.screens;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.client.resources.language.I18n;
import pro.mikey.xray.XRay;
import pro.mikey.xray.core.ScanController;
import pro.mikey.xray.screens.helpers.GuiBase;

import java.util.ArrayList;
import java.util.List;

/**
 * Settings screen for the render/HUD options added alongside the distance fade:
 * density compass, distance fade, the optional scan height band, and cave avoidance.
 * The panel fits seven rows; the rest is reached by scrolling. Save/cancel stay pinned
 * inside the panel below the scroll window.
 *
 * Panel geometry (from GuiBase): the 229x235 background spans
 * x in [w/2-113, w/2+116], y in [h/2-117, h/2+118]; the title sits at h/2-104.
 */
public class SettingsScreen extends GuiBase {
    private static final int ROW_HEIGHT = 24;
    private static final int CONTENT_ROWS = 7;

    private final List<Row> rows = new ArrayList<>();
    private double scrollOffset = 0;
    private int maxScroll = 0;

    private EditBox fadeStart;
    private EditBox fadeEnd;
    private EditBox fadeMinAlpha;
    private EditBox heightAbove;
    private EditBox heightBelow;
    private EditBox caveCheckDepth;
    private EditBox caveCheckMinAir;
    private EditBox bedrockCheckDepth;

    private record Row(AbstractWidget widget, String labelKey) {}

    public SettingsScreen() {
        super(false);
        this.setSize(229, 235);
    }

    @Override
    public boolean hasTitle() {
        return true;
    }

    @Override
    public String title() {
        return I18n.get("xray.title.settings");
    }

    /** Top of the scrolling content window: below the panel title. */
    private int contentTop() {
        return getHeight() / 2 - 90;
    }

    private int contentBottom() {
        return contentTop() + CONTENT_ROWS * ROW_HEIGHT;
    }

    private int labelX() {
        return getWidth() / 2 - 99; // panel interior left margin
    }

    private int controlX() {
        return getWidth() / 2 + 2; // 100-wide control ends at w/2+102, inside the panel
    }

    /** Natural (unscrolled) Y of a content row. */
    private int rowY(int row) {
        return contentTop() + row * ROW_HEIGHT;
    }

    /** A row fits when it is fully inside the scroll window. */
    private boolean rowVisible(int y) {
        return y >= contentTop() && y + 20 <= contentBottom();
    }

    @Override
    public void init() {
        assert minecraft != null;

        this.children().clear();
        this.rows.clear();
        this.scrollOffset = 0;
        var config = XRay.config();

        // --- Density compass toggle ---
        addToggle("xray.input.compass", "xray.label.compass", config.showDensityCompass);

        // --- Distance fade ---
        this.fadeStart = addNumber("xray.label.fade_start", config.fadeStartDistance.get(), "xray.tooltips.fade_start");
        this.fadeEnd = addNumber("xray.label.fade_end", config.fadeEndDistance.get(), "xray.tooltips.fade_end");
        this.fadeMinAlpha = addNumber("xray.label.fade_alpha", config.fadeMinAlpha.get(), "xray.tooltips.fade_alpha");

        // --- Scan height band ---
        addToggle("xray.input.height_limit", "xray.label.height_limit", config.useScanHeightLimit);

        this.heightAbove = addNumber("xray.label.height_above", config.scanHeightAbove.get(), "xray.tooltips.height_above");
        this.heightBelow = addNumber("xray.label.height_below", config.scanHeightBelow.get(), "xray.tooltips.height_below");

        // --- Cave avoidance ---
        addToggle("xray.input.cave_avoidance", "xray.label.cave_avoidance", config.caveAvoidance);

        this.caveCheckDepth = addNumber("xray.label.cave_depth", config.caveCheckDepth.get(), "xray.tooltips.cave_depth");
        this.caveCheckMinAir = addNumber("xray.label.cave_min_air", config.caveCheckMinAir.get(), "xray.tooltips.cave_min_air");

        // --- Lava avoidance ---
        addToggle("xray.input.lava_avoidance", "xray.label.lava_avoidance", config.lavaAvoidance);

        // --- Bedrock avoidance ---
        addToggle("xray.input.bedrock_avoidance", "xray.label.bedrock_avoidance", config.bedrockAvoidance);

        this.bedrockCheckDepth = addNumber("xray.label.bedrock_depth", config.bedrockCheckDepth.get(), "xray.tooltips.bedrock_depth");

        // --- Save / cancel: pinned inside the panel below the scroll window ---
        Button save = Button.builder(Component.translatable("xray.single.save"), btn -> saveAndClose())
                .pos(labelX(), contentBottom() + 3)
                .size(100, 20)
                .build();
        addRenderableWidget(save);

        Button cancel = Button.builder(Component.translatable("xray.single.cancel"), btn -> {
                    minecraft.setScreen(new ScanManageScreen());
                })
                .pos(controlX(), contentBottom() + 3)
                .size(100, 20)
                .build();
        addRenderableWidget(cancel);

        // 13 content rows, 7 visible at a time
        maxScroll = Math.max(0, 13 * ROW_HEIGHT - CONTENT_ROWS * ROW_HEIGHT);
        layout();
    }

    private void addToggle(String key, String labelKey, pro.mikey.xray.Configuration.ConfigValue<Boolean> value) {
        // Read live state on every click so repeated toggles work
        Button button = Button.builder(message(key, value.get()), btn -> {
                    boolean newValue = !value.get();
                    value.set(newValue);
                    btn.setMessage(message(key, newValue));
                })
                .size(100, 20)
                .build();
        rows.add(new Row(button, labelKey));
        addRenderableWidget(button);
    }

    private EditBox addNumber(String labelKey, int value, String tooltipKey) {
        EditBox box = new EditBox(getFontRender(), controlX(), 0, 100, 18, Component.empty());
        box.setValue(String.valueOf(value));
        box.setHint(Component.translatable(tooltipKey));
        box.setTooltip(Tooltip.create(Component.translatable(tooltipKey)));
        box.setCanLoseFocus(true);
        rows.add(new Row(box, labelKey));
        return addRenderableWidget(box);
    }

    /** Places every content row at its scrolled position, hiding rows outside the window. */
    private void layout() {
        for (int i = 0; i < rows.size(); i++) {
            AbstractWidget widget = rows.get(i).widget();
            int y = rowY(i) - (int) scrollOffset;

            widget.setX(controlX());
            widget.setY(y);
            widget.visible = rowVisible(y);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double xAmount, double yAmount) {
        scrollOffset = clamp(scrollOffset - yAmount * ROW_HEIGHT, 0, maxScroll);
        layout();
        return true;
    }

    private Component message(String key, boolean value) {
        return Component.translatable(key, Component.translatable(value ? "xray.common.on" : "xray.common.off"));
    }

    private void saveAndClose() {
        var config = XRay.config();

        int fadeStart = clamp(parseInt(this.fadeStart), 0, 512);
        int fadeEnd = clamp(parseInt(this.fadeEnd), 1, 512);
        // Keep the fade range sane: end must be past start
        if (fadeEnd <= fadeStart) {
            fadeEnd = fadeStart + 1;
        }

        config.fadeStartDistance.set(fadeStart);
        config.fadeEndDistance.set(fadeEnd);
        config.fadeMinAlpha.set(clamp(parseInt(this.fadeMinAlpha), 1, 100));
        config.scanHeightAbove.set(clamp(parseInt(this.heightAbove), 0, 384));
        config.scanHeightBelow.set(clamp(parseInt(this.heightBelow), 0, 384));
        config.caveCheckDepth.set(clamp(parseInt(this.caveCheckDepth), 1, 64));
        config.caveCheckMinAir.set(clamp(parseInt(this.caveCheckMinAir), 1, 64));
        config.bedrockCheckDepth.set(clamp(parseInt(this.bedrockCheckDepth), 0, 64));

        minecraft.setScreen(new ScanManageScreen());
    }

    private int parseInt(EditBox box) {
        try {
            return Integer.parseInt(box.getValue().trim());
        } catch (NumberFormatException e) {
            return -1; // clamp will pull it to the minimum valid value
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    public void renderExtra(GuiGraphics graphics, int x, int y, float partialTicks) {
        // Labels come from the rows themselves (no %s placeholder keys) and render
        // without a drop shadow so scrolling text doesn't smear
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            if (row.labelKey() == null) {
                continue;
            }

            int labelY = rowY(i) - (int) scrollOffset;
            if (rowVisible(labelY)) {
                graphics.drawString(getFontRender(), I18n.get(row.labelKey()), labelX(), labelY + 6, 0xFF404040);
            }
        }
    }

    @Override
    public void removed() {
        // Re-scan so height limit changes apply immediately
        ScanController.INSTANCE.requestBlockFinder(true);
        super.removed();
    }
}
