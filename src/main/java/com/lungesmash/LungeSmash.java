package com.lungesmash;

import java.util.Locale;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;

/**
 * LungeSmash: spear + mace combo helper.
 *
 * LUNGE SWAP (attribute swap): select an EMPTY hotbar slot (your "fist") and left-click. For that
 * one click the mod switches to your best Lunge spear so the jab fires with the fist's fast attack
 * cooldown, then switches back to the empty slot on the next tick. This automates a hotbar swap, so
 * check that your server allows it. Toggle with /lungesmash swap.
 *
 * HUD: a card above the hunger bar showing spear type and Lunge level, lunges left (also as a number
 * on the spear's hotbar slot), lunge-swap readiness, the mace's smash damage and fall meter, and a
 * "SWAP TO MACE" banner while you fall with the spear in hand. Toggle with /lungesmash.
 *
 * About the hunger readout: the server only sends saturation to the client when food or health
 * changes (or saturation hits zero), so the client's value goes stale. The mod therefore counts your
 * own lunges and subtracts their cost until the next real update arrives.
 */
public class LungeSmash implements ClientModInitializer {

    private static final double MACE_BASE_DAMAGE = 6.0;
    private static final double MIN_SMASH_FALL = 1.5;
    private static final double METER_MAX_FALL = 8.0;

    private static final int WHITE = 0xFFFFFFFF;
    private static final int GREY = 0xFFAAAAAA;
    private static final int CYAN = 0xFF55FFFF;
    private static final int GREEN = 0xFF55FF55;
    private static final int YELLOW = 0xFFFFFF55;
    private static final int RED = 0xFFFF5555;
    private static final int ORANGE = 0xFFFFAA00;
    private static final int TRACK = 0xFF2B2B2B;

    private static final int CARD_W = 136;
    private static final int PAD = 4;
    private static final int INNER_W = CARD_W - 2 * PAD;
    private static final int MAX_PIPS = 8;

    private static boolean enabled = true;
    private static boolean swapEnabled = true;

    // Lunge tracking (client-side estimate of food points spent since the last server update)
    private static int lastFood = -1;
    private static float lastSaturation = -1.0F;
    private static double pendingPoints = 0.0;

    // Attack key tracking
    private static boolean sawAttackDown = false;
    private static boolean wasPressed = false;

    // Lunge swap state
    private static boolean swapped = false;
    private static int returnSlot = 0;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.START_CLIENT_TICK.register(LungeSmash::onStartTick);
        ClientTickEvents.END_CLIENT_TICK.register(LungeSmash::onEndTick);
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("lungesmash", "hud"), LungeSmash::render);

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("lungesmash")
                        .executes(ctx -> {
                            enabled = !enabled;
                            ctx.getSource().sendFeedback(Component.literal("LungeSmash HUD " + (enabled ? "enabled" : "disabled")));
                            return 1;
                        })
                        .then(ClientCommandManager.literal("swap").executes(ctx -> {
                            swapEnabled = !swapEnabled;
                            ctx.getSource().sendFeedback(Component.literal("Lunge swap " + (swapEnabled ? "enabled" : "disabled")));
                            return 1;
                        }))));
    }

    // ---------------------------------------------------------------- ticking

    private static void onStartTick(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null) {
            lastFood = -1;
            lastSaturation = -1.0F;
            pendingPoints = 0.0;
            sawAttackDown = false;
            wasPressed = false;
            swapped = false;
            return;
        }

        var food = player.getFoodData();
        int foodLevel = food.getFoodLevel();
        float saturation = food.getSaturationLevel();

        // A change in food or saturation means the server just sent a fresh value:
        // it already includes every lunge so far, so start counting again from zero.
        if (foodLevel != lastFood || saturation != lastSaturation) {
            pendingPoints = 0.0;
            lastFood = foodLevel;
            lastSaturation = saturation;
        }

        // The attack key counts as pressed if it is down now or was seen down in a frame since the
        // last tick (so quick taps are not missed). Only the first tick of a press triggers.
        boolean pressed = mc.screen == null && (mc.options.keyAttack.isDown() || sawAttackDown);
        sawAttackDown = false;
        boolean trigger = pressed && !wasPressed;
        wasPressed = pressed;
        if (!trigger) {
            return;
        }

        ItemStack held = player.getMainHandItem();
        boolean fullCharge = player.getAttackStrengthScale(0.0F) >= 1.0F;

        if (isSpear(held)) {
            // Normal jab with the spear itself.
            int level = lungeLevel(held);
            if (level > 0 && foodLevel > 6 && fullCharge) {
                pendingPoints += level; // one lunge = 4 exhaustion per level = 1 point per level
            }
        } else if (swapEnabled && held.isEmpty() && foodLevel > 6 && fullCharge) {
            // Lunge swap: this click will be handled later this tick, with the spear selected.
            int[] best = bestSpear(player);
            if (best != null) {
                var inventory = player.getInventory();
                returnSlot = inventory.getSelectedSlot();
                inventory.setSelectedSlot(best[0]);
                swapped = true;
                pendingPoints += best[1];
            }
        }
    }

    private static void onEndTick(Minecraft mc) {
        if (swapped) {
            swapped = false;
            LocalPlayer player = mc.player;
            if (player != null) {
                player.getInventory().setSelectedSlot(returnSlot);
            }
        }
    }

    /** Best Lunge spear in the hotbar as {slot, level}, or null if there is none. */
    private static int[] bestSpear(LocalPlayer player) {
        int slot = -1;
        int level = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (isSpear(stack)) {
                int l = lungeLevel(stack);
                if (l > level) {
                    slot = i;
                    level = l;
                }
            }
        }
        return slot < 0 ? null : new int[] {slot, level};
    }

    // -------------------------------------------------------------- rendering

    private static void render(GuiGraphics graphics, DeltaTracker tickCounter) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.keyAttack.isDown()) {
            sawAttackDown = true; // frame-rate polling so short clicks are caught by the tick logic
        }
        if (!enabled) {
            return;
        }
        LocalPlayer player = mc.player;
        if (player == null) {
            return;
        }

        ItemStack held = player.getMainHandItem();
        boolean holdingSpear = isSpear(held);

        // Find the best spear and the mace in the hotbar.
        int spearSlot = -1;
        int lunge = 0;
        ItemStack spear = ItemStack.EMPTY;
        int maceSlot = -1;
        ItemStack mace = ItemStack.EMPTY;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (isSpear(stack)) {
                int level = lungeLevel(stack);
                if (spearSlot < 0 || level > lunge) {
                    spearSlot = i;
                    lunge = level;
                    spear = stack;
                }
            } else if (maceSlot < 0 && stack.is(Items.MACE)) {
                maceSlot = i;
                mace = stack;
            }
        }
        if (spearSlot < 0 && maceSlot < 0) {
            return;
        }

        double fall = Math.max(0.0, player.fallDistance);
        boolean smashReady = fall > MIN_SMASH_FALL && !player.isFallFlying();

        // Estimated food and saturation: last real values minus lunges the server hasn't reported yet.
        var food = player.getFoodData();
        double estSaturation = food.getSaturationLevel();
        double estFood = food.getFoodLevel();
        double remaining = pendingPoints;
        double fromSaturation = Math.min(estSaturation, remaining);
        estSaturation -= fromSaturation;
        remaining -= fromSaturation;
        estFood = Math.max(0.0, estFood - remaining);

        boolean hasLunge = spearSlot >= 0 && lunge > 0;
        boolean canLunge = hasLunge && estFood > 6.0;
        int lunges = 0;
        if (canLunge) {
            double spendable = (estFood - 6.0) + estSaturation;
            lunges = Math.max(1, (int) (spendable / lunge));
        }
        int lungeColor = !canLunge ? RED : (lunges >= 6 ? GREEN : (lunges >= 3 ? YELLOW : RED));

        boolean banner = holdingSpear && smashReady && maceSlot >= 0;
        boolean swapRow = hasLunge && swapEnabled;
        boolean jabRow = spearSlot >= 0 && holdingSpear && !swapRow;

        // ---- card size
        int content = 0;
        if (banner) {
            content += 14;
        }
        if (spearSlot >= 0) {
            content += 20;
        }
        if (hasLunge) {
            content += 20;
        }
        if (swapRow || jabRow) {
            content += 12;
        }
        if (maceSlot >= 0) {
            content += 21;
        }
        int cardH = content + 2 + PAD * 2;

        Font font = mc.font;
        int cx = graphics.guiWidth() / 2;
        int guiHeight = graphics.guiHeight();

        // Above the hunger bar: right-aligned with the hotbar, clear of air bubbles and the item name.
        int x = cx + 91 - CARD_W;
        int bottom = guiHeight - 62;
        int top = bottom - cardH;
        int accent = banner ? ORANGE : (hasLunge ? lungeColor : CYAN);

        graphics.fill(x - 1, top - 1, x + CARD_W + 1, bottom + 1, 0xFF3C3C3C);
        graphics.fill(x, top, x + CARD_W, bottom, 0xF0121212);
        graphics.fill(x, top, x + CARD_W, top + 2, accent);

        int ix = x + PAD;
        int cy = top + 2 + PAD;

        // ---- swap-to-mace banner
        if (banner) {
            boolean pulse = (System.currentTimeMillis() / 250) % 2 == 0;
            graphics.fill(ix, cy, ix + INNER_W, cy + 12, pulse ? 0xFFCC7700 : 0xFF994F00);
            String text = "SWAP TO MACE [" + (maceSlot + 1) + "]";
            graphics.drawString(font, text, ix + (INNER_W - font.width(text)) / 2, cy + 2, WHITE);
            cy += 14;
        }

        // ---- spear header
        if (spearSlot >= 0) {
            graphics.renderItem(spear, ix, cy);
            graphics.drawString(font, spearName(spear), ix + 20, cy, WHITE);
            String sub = (lunge > 0 ? "Lunge " + roman(lunge) : "no Lunge") + "  [" + (spearSlot + 1) + "]";
            graphics.drawString(font, sub, ix + 20, cy + 9, lunge > 0 ? CYAN : GREY);
            cy += 20;
        }

        // ---- lunges left + food
        if (hasLunge) {
            String label = canLunge ? "Lunges: " + lunges : "NO LUNGE";
            graphics.drawString(font, label, ix, cy, lungeColor);
            int pipsWidth = MAX_PIPS * 7 - 1;
            int px = ix + INNER_W - pipsWidth;
            int shown = canLunge ? Math.min(lunges, MAX_PIPS) : 0;
            for (int i = 0; i < MAX_PIPS; i++) {
                graphics.fill(px, cy + 2, px + 6, cy + 7, i < shown ? lungeColor : TRACK);
                px += 7;
            }
            cy += 10;
            String foodText = canLunge
                    ? "food " + (int) estFood + "  sat ~" + fmt(estSaturation)
                    : "food " + (int) estFood + " (needs 7+)";
            graphics.drawString(font, foodText, ix, cy, GREY);
            cy += 10;
        }

        // ---- lunge swap readiness (or plain jab readiness when swap is off)
        if (swapRow || jabRow) {
            float charge = player.getAttackStrengthScale(0.0F);
            int pct = (int) (charge * 100);
            String text;
            int color;
            double frac;
            if (swapRow) {
                if (held.isEmpty()) {
                    frac = charge;
                    text = charge >= 1.0F ? "LUNGE SWAP READY" : "LUNGE SWAP " + pct + "%";
                    color = charge >= 1.0F ? GREEN : YELLOW;
                } else {
                    frac = 0.0;
                    text = "select empty slot";
                    color = GREY;
                }
            } else {
                frac = charge;
                text = charge >= 1.0F ? "JAB READY" : "JAB " + pct + "%";
                color = charge >= 1.0F ? GREEN : YELLOW;
            }
            bar(graphics, ix, cy, INNER_W, 9, frac, tint(color));
            graphics.drawString(font, text, ix + (INNER_W - font.width(text)) / 2, cy + 1, WHITE);
            cy += 12;
        }

        // ---- mace
        if (maceSlot >= 0) {
            graphics.renderItem(mace, ix, cy);
            graphics.drawString(font, "Mace [" + (maceSlot + 1) + "]", ix + 20, cy, WHITE);
            String sub;
            int subColor;
            if (smashReady) {
                double dmg = smashDamage(mace, fall);
                sub = "smash ~" + fmt(dmg) + " dmg";
                subColor = dmg >= 40 ? RED : (dmg >= 20 ? ORANGE : GREEN);
            } else {
                sub = "fall 1.5+ to smash";
                subColor = GREY;
            }
            graphics.drawString(font, sub, ix + 20, cy + 9, subColor);

            int meterY = cy + 18;
            bar(graphics, ix, meterY, INNER_W, 3, fall / METER_MAX_FALL, smashReady ? subColor : GREY);
            int tick = ix + (int) (INNER_W * (MIN_SMASH_FALL / METER_MAX_FALL));
            graphics.fill(tick, meterY - 1, tick + 1, meterY + 4, WHITE);
        }

        // ---- lunges-left number on the spear's own hotbar slot (like a stack count)
        if (hasLunge) {
            String badge = String.valueOf(lunges);
            int itemX = cx - 88 + spearSlot * 20;
            int itemY = guiHeight - 19;
            graphics.drawString(font, badge, itemX + 17 - font.width(badge), itemY + 9, lungeColor);
        }
    }

    private static void bar(GuiGraphics graphics, int x, int y, int w, int h, double fraction, int color) {
        graphics.fill(x, y, x + w, y + h, TRACK);
        int filled = (int) Math.round(w * Math.max(0.0, Math.min(1.0, fraction)));
        if (filled > 0) {
            graphics.fill(x, y, x + filled, y + h, color);
        }
    }

    /** Same color, partly transparent, so text drawn on top of a bar stays readable. */
    private static int tint(int color) {
        return (color & 0x00FFFFFF) | 0xB0000000;
    }

    // ---------------------------------------------------------------- helpers

    private static boolean isSpear(ItemStack stack) {
        return !stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().endsWith("_spear");
    }

    /** "diamond_spear" -> "Diamond Spear" */
    private static String spearName(ItemStack stack) {
        String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        StringBuilder name = new StringBuilder();
        for (String part : path.split("_")) {
            if (part.isEmpty()) {
                continue;
            }
            if (name.length() > 0) {
                name.append(' ');
            }
            name.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return name.toString();
    }

    private static int lungeLevel(ItemStack stack) {
        ItemEnchantments enchants = stack.get(DataComponents.ENCHANTMENTS);
        if (enchants == null) {
            return 0;
        }
        for (var entry : enchants.entrySet()) {
            if (entry.getKey().is(Enchantments.LUNGE)) {
                return entry.getIntValue();
            }
        }
        return 0;
    }

    private static int densityLevel(ItemStack stack) {
        ItemEnchantments enchants = stack.get(DataComponents.ENCHANTMENTS);
        if (enchants == null) {
            return 0;
        }
        for (var entry : enchants.entrySet()) {
            if (entry.getKey().is(Enchantments.DENSITY)) {
                return entry.getIntValue();
            }
        }
        return 0;
    }

    /** Raw smash damage: base + fall bonus (+4/block to 3, +2/block to 8, then +1/block) + Density. */
    private static double smashDamage(ItemStack mace, double fall) {
        double bonus;
        if (fall <= 3.0) {
            bonus = 4.0 * fall;
        } else if (fall <= 8.0) {
            bonus = 12.0 + 2.0 * (fall - 3.0);
        } else {
            bonus = 22.0 + (fall - 8.0);
        }
        return MACE_BASE_DAMAGE + bonus + 0.5 * densityLevel(mace) * fall;
    }

    private static String roman(int level) {
        return switch (level) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            default -> String.valueOf(level);
        };
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
