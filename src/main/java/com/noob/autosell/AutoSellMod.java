// lang: Java, file: AutoSellMod.java
// target: Fabric 1.21.1, client-side
// server: DonutSMP — GUI sell chest ("Sell" title), green button = confirm
// build: Fabric Loom, Java 21
// keybind: R (toggle ON/OFF) — hoạt động cả khi GUI đang mở

package com.noob.autosell;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.lwjgl.glfw.GLFW;

public class AutoSellMod implements ClientModInitializer {

    // ── config ────────────────────────────────────────────────────────────────
    private static final String  SELL_SCREEN_TITLE      = "Sell";
    private static final int     GREEN_BUTTON_SLOT      = 53;   // slot nút xanh (debug nếu sai)
    private static final int     TRIGGER_EMPTY_THRESHOLD = 9;   // flush khi sell area còn < 9 slot trống
    private static final int     ACTION_DELAY           = 3;    // tick giữa mỗi click
    private static final boolean DEBUG_MODE             = false; // bật để tìm slot nút xanh

    // ── keybind ───────────────────────────────────────────────────────────────
    // mặc định: R — đổi trong Options > Controls > AutoSell
    private static KeyBinding toggleKey;

    // ── state ─────────────────────────────────────────────────────────────────
    private static boolean running     = false;
    private static int     tickTimer   = 0;
    private static int     cycleCount  = 0;

    private enum Phase { IDLE, MOVING_ITEMS, CLICKING_BUTTON, WAITING_REOPEN }
    private static Phase phase      = Phase.IDLE;
    private static int   phaseTimer = 0;
    private static int   hotbarIndex = 0;

    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void onInitializeClient() {
        // đăng ký keybind — hiện trong Options > Controls > AutoSell
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.autosell.toggle",          // translation key
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_R,                // default: R
            "category.autosell"             // category trong Controls menu
        ));

        registerTickLoop();
    }

    // ── tick loop ─────────────────────────────────────────────────────────────
    private void registerTickLoop() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;

            // poll keybind — wasPressed() consume event, hoạt động kể cả khi GUI mở
            while (toggleKey.wasPressed()) {
                toggle(client);
            }

            if (!running) return;
            if (client.getNetworkHandler() == null) { running = false; return; }

            // chỉ chạy khi đúng cửa sổ Sell
            if (!(client.currentScreen instanceof GenericContainerScreen sellScreen)) {
                if (phase != Phase.IDLE) phase = Phase.IDLE;
                return;
            }
            if (!sellScreen.getTitle().getString().equalsIgnoreCase(SELL_SCREEN_TITLE)) return;

            GenericContainerScreenHandler handler =
                (GenericContainerScreenHandler) sellScreen.getScreenHandler();

            if (DEBUG_MODE) debugSlots(client.player, handler);

            tickTimer++;
            if (tickTimer < ACTION_DELAY) return;
            tickTimer = 0;

            switch (phase) {
                case IDLE -> {
                    hotbarIndex = 0;
                    phase = Phase.MOVING_ITEMS;
                }

                case MOVING_ITEMS -> {
                    if (countEmptySellSlots(handler) < TRIGGER_EMPTY_THRESHOLD) {
                        phase = Phase.CLICKING_BUTTON;
                        return;
                    }

                    boolean moved = false;
                    while (hotbarIndex < 9) {
                        int screenSlot = handler.getRows() * 9 + 27 + hotbarIndex;
                        ItemStack stack = handler.getSlot(screenSlot).getStack();
                        if (!stack.isEmpty()) {
                            client.interactionManager.clickSlot(
                                handler.syncId, screenSlot, 0,
                                SlotActionType.QUICK_MOVE, client.player
                            );
                            moved = true;
                        }
                        hotbarIndex++;
                        if (moved) return;
                    }
                    phase = Phase.CLICKING_BUTTON;
                }

                case CLICKING_BUTTON -> {
                    client.interactionManager.clickSlot(
                        handler.syncId, GREEN_BUTTON_SLOT, 0,
                        SlotActionType.PICKUP, client.player
                    );
                    cycleCount++;
                    phaseTimer  = 0;
                    hotbarIndex = 0;
                    phase = Phase.WAITING_REOPEN;

                    client.player.sendMessage(
                        Text.literal("[AutoSell] cycle " + cycleCount)
                            .formatted(Formatting.DARK_AQUA),
                        true
                    );
                }

                case WAITING_REOPEN -> {
                    phaseTimer++;
                    if (phaseTimer >= 10) phase = Phase.IDLE;
                }
            }
        });
    }

    // ── toggle ────────────────────────────────────────────────────────────────
    private static void toggle(MinecraftClient client) {
        running = !running;
        if (running) {
            cycleCount  = 0;
            phase       = Phase.IDLE;
            hotbarIndex = 0;
            client.player.sendMessage(
                Text.literal("[AutoSell] ").formatted(Formatting.AQUA)
                    .append(Text.literal("ON — nhấn R lại để tắt")
                        .formatted(Formatting.GREEN)),
                false
            );
        } else {
            phase = Phase.IDLE;
            client.player.sendMessage(
                Text.literal("[AutoSell] ").formatted(Formatting.AQUA)
                    .append(Text.literal("OFF — " + cycleCount + " cycles")
                        .formatted(Formatting.RED)),
                false
            );
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────
    private int countEmptySellSlots(GenericContainerScreenHandler handler) {
        int empty = 0;
        int sellSlots = (handler.getRows() - 1) * 9;
        for (int i = 0; i < sellSlots; i++) {
            if (handler.getSlot(i).getStack().isEmpty()) empty++;
        }
        return empty;
    }

    private void debugSlots(ClientPlayerEntity player, GenericContainerScreenHandler handler) {
        for (int i = 0; i < handler.slots.size(); i++) {
            ItemStack s = handler.getSlot(i).getStack();
            if (!s.isEmpty()) {
                player.sendMessage(
                    Text.literal("[DBG] slot " + i + " = " + s.getName().getString())
                        .formatted(Formatting.GRAY),
                    false
                );
            }
        }
    }

    public static boolean isRunning() { return running; }
    public static int     getCycles() { return cycleCount; }
}
