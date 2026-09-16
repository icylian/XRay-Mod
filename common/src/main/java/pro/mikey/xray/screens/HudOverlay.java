package pro.mikey.xray.screens;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import pro.mikey.xray.XRay;
import pro.mikey.xray.core.ScanController;

public class HudOverlay {
    private static final Identifier CIRCLE = XRay.assetLocation("gui/circle.png");

    public static void renderGameOverlayEvent(GuiGraphics graphics) {
        if(!ScanController.INSTANCE.isXRayActive())
            return;

        GpuDevice gpuDevice = RenderSystem.tryGetDevice();
        boolean renderDebug = gpuDevice != null && gpuDevice.isDebuggingEnabled();

        if (XRay.config().showOverlay.get()) {
            int x = 5, y = 5;
            if (renderDebug) {
                x = Minecraft.getInstance().getWindow().getGuiScaledWidth() - 10;
                y = Minecraft.getInstance().getWindow().getGuiScaledHeight() - 10;
            }

            graphics.blit(RenderPipelines.GUI_TEXTURED, CIRCLE, x, y, 0f, 0f, 5, 5, 5, 5, 0xFF00FF00);

            int width = Minecraft.getInstance().font.width(I18n.get("xray.overlay"));
            graphics.drawString(Minecraft.getInstance().font, I18n.get("xray.overlay"), x + (!renderDebug ? 10 : -width - 5), y - (!renderDebug ? 1 : 2), 0xff00ff00);
        }

        if (XRay.config().showDensityCompass.get()) {
            renderDensityCompass(graphics);
        }
    }

    private static void renderDensityCompass(GuiGraphics graphics) {
        var density = ScanController.INSTANCE.getDensityCounts();
        var font = Minecraft.getInstance().font;

        // Centered above the hotbar so chat and potion icons don't cover it
        int screenWidth = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        int y = Minecraft.getInstance().getWindow().getGuiScaledHeight() - 92;

        // Brighter count -> brighter line, so the densest direction stands out at a glance
        int max = Math.max(Math.max(density.north(), density.east()), Math.max(density.south(), density.west()));

        // The direction the player is facing gets a bright cyan arrow prefix
        var player = Minecraft.getInstance().player;
        String facingDir = player == null ? "" : facingDirection(player);
        String facing = facingDir.isEmpty() ? "" : I18n.get("xray.compass.facing", facingDir);

        text(graphics, font, "xray.compass.north", density.north(), screenWidth, y, max, facingDir.equals("N"));
        text(graphics, font, "xray.compass.east", density.east(), screenWidth, y + 10, max, facingDir.equals("E"));
        text(graphics, font, "xray.compass.south", density.south(), screenWidth, y + 20, max, facingDir.equals("S"));
        text(graphics, font, "xray.compass.west", density.west(), screenWidth, y + 30, max, facingDir.equals("W"));

        if (!facing.isEmpty()) {
            graphics.drawString(font, facing, (screenWidth - font.width(facing)) / 2, y - 10, 0xFF66E6FF);
        }

        if (density.here() > 0) {
            String line = I18n.get("xray.compass.here", density.here());
            graphics.drawString(font, line, (screenWidth - font.width(line)) / 2, y + 40, 0xFFFFAA00);
        }
    }

    /** Resolves the player's yaw into the cardinal direction they are facing. */
    private static String facingDirection(net.minecraft.world.entity.player.Player player) {
        // Vanilla yaw: -90 = east, 0 = south, 90 = west, 180/-180 = north
        float yaw = Mth.wrapDegrees(player.getYRot());
        if (yaw >= -45 && yaw < 45) return "S";
        if (yaw >= 45 && yaw < 135) return "W";
        if (yaw < -45 && yaw >= -135) return "E";
        return "N";
    }

    private static void text(GuiGraphics graphics, net.minecraft.client.gui.Font font, String key, int count, int screenWidth, int y, int max, boolean isFacing) {
        int color = count > 0 ? (0xFF000000 | lerpColor(count, max)) : 0xFF555555;
        String line = (isFacing ? "▶ " : "") + I18n.get(key, count);
        graphics.drawString(font, line, (screenWidth - font.width(line)) / 2, y, isFacing ? 0xFF66E6FF : color);
    }

    private static int lerpColor(int count, int max) {
        // Dim gray-green for sparse directions up to bright green for the densest
        float t = max > 0 ? (float) count / max : 0.0f;
        int g = 0x55 + (int) ((0xFF - 0x55) * t);
        return (0x33 << 16) | (g << 8) | 0x33;
    }
}
