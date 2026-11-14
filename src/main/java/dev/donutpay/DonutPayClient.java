package dev.donutpay;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.util.Formatting;
import org.lwjgl.glfw.GLFW;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DonutPay
 *  - Orange [2x] [Bal] [Payback] buttons when someone pays you.
 *  - /dpay            opens the settings GUI (colors, HUD editor, session buttons)
 *  - /dpay start|stop starts / ends a profit-tracking session (HUD only shows during a session)
 *  - Nothing is ever sent automatically: every payment needs a click from you.
 */
public class DonutPayClient implements ClientModInitializer {

    // ------------------------------------------------------------------ colors
    static final String[] COLOR_NAMES = {"Orange", "Red", "Yellow", "Green", "Aqua", "Blue", "Purple", "Pink", "White"};
    static final int[] COLOR_VALUES = {0xFFA500, 0xFF5555, 0xFFFF55, 0x55FF55, 0x55FFFF, 0x5555FF, 0xAA55FF, 0xFF55FF, 0xFFFFFF};

    // ------------------------------------------------------------------ saved settings
    static int buttonColor = 0xFFA500;
    static int hudColor = 0xFFA500;
    static int hudX = 5;
    static int hudY = 40;
    static float hudScale = 1.0f;

    // ------------------------------------------------------------------ session
    static boolean active = false;
    static long startMs = 0;
    static long received = 0;
    static long paidOut = 0;
    static long taxPaid = 0;
    static int countIn = 0;
    static int countOut = 0;

    private static boolean debug = false;
    private static boolean openGui = false;

    // "Steve paid you $200K" (anchored at the start, so a player can't fake it by typing it in chat)
    private static final Pattern PAID = Pattern.compile(
            "^\\W*(\\w{3,16})\\s+(?:has\\s+)?(?:paid|sent)\\s+you\\s+(?:a total of\\s+)?\\$?\\s*([\\d,]*\\.?\\d+)\\s*([kKmMbBtT]?)\\b.{0,40}$",
            Pattern.CASE_INSENSITIVE);

    // "You paid Steve $ 1 (+$ 1 tax)"
    private static final Pattern PAID_OUT = Pattern.compile(
            "^\\W*You\\s+paid\\s+(\\w{3,16})\\s+\\$?\\s*([\\d,]*\\.?\\d+)\\s*([kKmMbBtT]?)\\b(.*)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TAX = Pattern.compile(
            "\\+\\s*\\$?\\s*([\\d,]*\\.?\\d+)\\s*([kKmMbBtT]?)\\s*tax", Pattern.CASE_INSENSITIVE);

    private static final Map<Integer, Payment> PAYMENTS = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, Payment> eldest) {
            return size() > 200;
        }
    };
    private static int nextId = 1;

    private static final class Payment {
        final String name;
        final long amount;
        boolean paidBack = false;

        Payment(String name, long amount) {
            this.name = name;
            this.amount = amount;
        }
    }

    // ================================================================== init
    @Override
    public void onInitializeClient() {
        loadConfig();

        // GAME = server/system messages. Player chat is ignored on purpose (spoof protection).
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay) return;
            handle(message.getString(), "GAME");
        });
        // Some servers send plugin messages through the chat packet. Only accept those with NO player sender.
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, ts) ->
                handle(message.getString(), sender == null ? "CHAT" : "PLAYER-CHAT"));

        // Opening a screen straight from a chat command gets closed again by the chat screen,
        // so we open it on the next tick instead.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (openGui) {
                openGui = false;
                client.setScreen(new SettingsScreen());
            }
        });

        // Profit HUD
        HudRenderCallback.EVENT.register((ctx, tickCounter) -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.options.hudHidden) return;
            if (!active) return;
            if (mc.currentScreen instanceof HudEditScreen) return; // the editor draws it itself
            drawHud(ctx, mc);
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("dpay")
                        .executes(ctx -> {
                            openGui = true;
                            return 1;
                        })
                        .then(ClientCommandManager.literal("gui").executes(ctx -> {
                            openGui = true;
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("start").executes(ctx -> {
                            startSession();
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("stop").executes(ctx -> {
                            stopSession();
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("reset").executes(ctx -> {
                            resetSession();
                            info("Session numbers reset.");
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("debug").executes(ctx -> {
                            debug = !debug;
                            info("Debug " + (debug ? "ON - pay messages will be shown as RAW" : "OFF"));
                            return 1;
                        }))
                        // internal: used by the clickable buttons
                        .then(ClientCommandManager.literal("act")
                                .then(ClientCommandManager.argument("id", IntegerArgumentType.integer())
                                        .then(ClientCommandManager.argument("action", StringArgumentType.word())
                                                .executes(ctx -> {
                                                    act(IntegerArgumentType.getInteger(ctx, "id"),
                                                            StringArgumentType.getString(ctx, "action"));
                                                    return 1;
                                                }))))
                        // /dpay test <name> <amount>  -> fake a payment to see the buttons
                        .then(ClientCommandManager.literal("test")
                                .then(ClientCommandManager.argument("name", StringArgumentType.word())
                                        .then(ClientCommandManager.argument("amount", LongArgumentType.longArg(1))
                                                .executes(ctx -> {
                                                    show(StringArgumentType.getString(ctx, "name"),
                                                            LongArgumentType.getLong(ctx, "amount"));
                                                    return 1;
                                                }))))));
    }

    // ================================================================== chat handling
    private static void handle(String raw, String source) {
        String s = Formatting.strip(raw);
        if (s == null) return;
        if (debug) {
            String low = s.toLowerCase();
            if (low.contains("paid") || low.contains("pay") || low.contains("sent") || low.contains("$")) {
                MinecraftClient.getInstance().execute(() -> info("RAW [" + source + "]: " + s));
            }
        }
        if (source.equals("PLAYER-CHAT")) return;
        s = s.trim();

        // money going OUT: "You paid Steve $ 1 (+$ 1 tax)"
        Matcher o = PAID_OUT.matcher(s);
        if (o.find()) {
            if (active) {
                long amt = parseAmount(o.group(2), o.group(3));
                if (amt > 0) {
                    paidOut += amt;
                    countOut++;
                    Matcher t = TAX.matcher(o.group(4));
                    if (t.find()) {
                        long tax = parseAmount(t.group(1), t.group(2));
                        if (tax > 0) taxPaid += tax;
                    }
                }
            }
            return;
        }

        // money coming IN: "Steve paid you $200K"
        Matcher m = PAID.matcher(s);
        if (!m.find()) return;

        long amount = parseAmount(m.group(2), m.group(3));
        if (amount <= 0) return;
        if (active) {
            received += amount;
            countIn++;
        }
        String name = m.group(1);
        MinecraftClient.getInstance().execute(() -> show(name, amount));
    }

    private static void show(String name, long amount) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.inGameHud == null) return;

        int id = nextId++;
        PAYMENTS.put(id, new Payment(name, amount));

        long doubled = amount * 2;
        MutableText line = Text.literal("» ").setStyle(Style.EMPTY.withColor(TextColor.fromRgb(buttonColor)));
        line.append(button("[2x]", id, "2x", "Pay " + name + " $" + compact(doubled)));
        line.append(Text.literal(" "));
        line.append(button("[Bal]", id, "bal", "Run /bal " + name));
        line.append(Text.literal(" "));
        line.append(button("[Payback]", id, "payback", "Pay " + name + " $" + compact(amount)));
        line.append(Text.literal("  " + name + " paid $" + compact(amount))
                .setStyle(Style.EMPTY.withColor(Formatting.GRAY)));

        mc.inGameHud.getChatHud().addMessage(line);
    }

    private static MutableText button(String label, int id, String action, String tooltip) {
        return Text.literal(label).setStyle(Style.EMPTY
                .withColor(TextColor.fromRgb(buttonColor))
                .withBold(true)
                .withClickEvent(new ClickEvent.RunCommand("/dpay act " + id + " " + action))
                .withHoverEvent(new HoverEvent.ShowText(Text.literal(tooltip))));
    }

    private static void act(int id, String action) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.getNetworkHandler() == null || mc.player == null) return;

        Payment p = PAYMENTS.get(id);
        if (p == null) {
            info("That payment is too old, button expired.");
            return;
        }

        switch (action) {
            case "bal" -> mc.getNetworkHandler().sendChatCommand("bal " + p.name);
            case "2x", "payback" -> {
                if (p.paidBack) {
                    info("You already paid " + p.name + " back for this one.");
                    return;
                }
                long out;
                try {
                    out = action.equals("2x") ? Math.multiplyExact(p.amount, 2L) : p.amount;
                } catch (ArithmeticException e) {
                    info("Amount too large.");
                    return;
                }
                p.paidBack = true;
                // plain digits so the server always understands the amount
                mc.getNetworkHandler().sendChatCommand("pay " + p.name + " " + out);
            }
            default -> info("Unknown action.");
        }
    }

    static void info(String msg) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.inGameHud != null) {
            mc.inGameHud.getChatHud().addMessage(Text.literal("[DonutPay] " + msg)
                    .setStyle(Style.EMPTY.withColor(TextColor.fromRgb(buttonColor))));
        }
    }

    // ================================================================== session
    static void startSession() {
        resetSession();
        active = true;
        startMs = System.currentTimeMillis();
        info("Session started. Tracking payments, use /dpay stop to end it.");
    }

    static void stopSession() {
        if (!active) {
            info("No session running. Use /dpay start.");
            return;
        }
        active = false;
        info("Session ended: received $" + compact(received) + ", paid out $" + compact(paidOut + taxPaid)
                + ", profit " + signedMoney(profit()) + " in " + elapsedText() + ".");
    }

    static void resetSession() {
        received = 0;
        paidOut = 0;
        taxPaid = 0;
        countIn = 0;
        countOut = 0;
        startMs = System.currentTimeMillis();
    }

    static long profit() {
        return received - paidOut - taxPaid;
    }

    static String elapsedText() {
        long sec = Math.max(0, (System.currentTimeMillis() - startMs) / 1000);
        long h = sec / 3600, m = (sec % 3600) / 60, s = sec % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%d:%02d", m, s);
    }

    static String signedMoney(long v) {
        return (v < 0 ? "-$" : "$") + compact(Math.abs(v));
    }

    // ================================================================== HUD drawing
    private static final int PAD = 4;
    private static final int LINE_H = 10;

    private static String[] hudLines() {
        return new String[]{
                "DonutPay Profit",
                "Received: $" + compact(received) + " (" + countIn + ")",
                "Paid out: $" + compact(paidOut + taxPaid) + " (" + countOut + ")",
                "Profit: " + signedMoney(profit()),
                "Time: " + elapsedText()
        };
    }

    private static int[] hudLineColors() {
        return new int[]{hudColor, 0x55FF55, 0xFF5555, profit() >= 0 ? 0x55FF55 : 0xFF5555, 0xAAAAAA};
    }

    static int hudBaseWidth(TextRenderer tr) {
        int w = 0;
        for (String l : hudLines()) w = Math.max(w, tr.getWidth(l));
        return w + PAD * 2;
    }

    static int hudBaseHeight() {
        return hudLines().length * LINE_H + PAD * 2 - 1;
    }

    static int hudWidth(TextRenderer tr) {
        return Math.round(hudBaseWidth(tr) * hudScale);
    }

    static int hudHeight() {
        return Math.round(hudBaseHeight() * hudScale);
    }

    static void clampHud(MinecraftClient mc, int screenW, int screenH) {
        int w = hudWidth(mc.textRenderer);
        int h = hudHeight();
        hudX = Math.max(0, Math.min(hudX, Math.max(0, screenW - w)));
        hudY = Math.max(0, Math.min(hudY, Math.max(0, screenH - h)));
    }

    static void drawHud(DrawContext ctx, MinecraftClient mc) {
        TextRenderer tr = mc.textRenderer;
        clampHud(mc, ctx.getScaledWindowWidth(), ctx.getScaledWindowHeight());

        String[] lines = hudLines();
        int[] colors = hudLineColors();
        int w = hudBaseWidth(tr);
        int h = hudBaseHeight();

        var m = ctx.getMatrices();
        m.pushMatrix();
        m.translate((float) hudX, (float) hudY);
        m.scale(hudScale, hudScale);

        ctx.fill(0, 0, w, h, 0xB0000000);
        ctx.drawStrokedRectangle(0, 0, w, h, 0xFF000000 | hudColor);
        for (int i = 0; i < lines.length; i++) {
            ctx.drawText(tr, lines[i], PAD, PAD + i * LINE_H, 0xFF000000 | colors[i], true);
        }
        m.popMatrix();
    }

    // ================================================================== config file
    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("donutpay.json");
    }

    static void loadConfig() {
        try {
            Path p = configPath();
            if (!Files.exists(p)) return;
            JsonObject o = JsonParser.parseString(Files.readString(p)).getAsJsonObject();
            if (o.has("buttonColor")) buttonColor = o.get("buttonColor").getAsInt();
            if (o.has("hudColor")) hudColor = o.get("hudColor").getAsInt();
            if (o.has("hudX")) hudX = o.get("hudX").getAsInt();
            if (o.has("hudY")) hudY = o.get("hudY").getAsInt();
            if (o.has("hudScale")) hudScale = Math.max(0.5f, Math.min(3.0f, o.get("hudScale").getAsFloat()));
        } catch (Exception ignored) {
            // bad or missing config: just use defaults
        }
    }

    static void saveConfig() {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("buttonColor", buttonColor);
            o.addProperty("hudColor", hudColor);
            o.addProperty("hudX", hudX);
            o.addProperty("hudY", hudY);
            o.addProperty("hudScale", hudScale);
            Files.writeString(configPath(), o.toString());
        } catch (Exception ignored) {
            // not fatal
        }
    }

    // ================================================================== amounts
    /** "200" + "k" -> 200000, "1.5" + "m" -> 1500000 */
    private static long parseAmount(String num, String suffix) {
        try {
            BigDecimal v = new BigDecimal(num.replace(",", ""));
            long mult = switch (suffix.toLowerCase()) {
                case "k" -> 1_000L;
                case "m" -> 1_000_000L;
                case "b" -> 1_000_000_000L;
                case "t" -> 1_000_000_000_000L;
                default -> 1L;
            };
            return v.multiply(BigDecimal.valueOf(mult)).longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            return -1;
        }
    }

    /** 400000 -> "400K", 2500000 -> "2.5M" (display only) */
    static String compact(long n) {
        String[] suf = {"", "K", "M", "B", "T"};
        double v = n;
        int i = 0;
        while (v >= 1000 && i < suf.length - 1) {
            v /= 1000;
            i++;
        }
        String s = new BigDecimal(v).setScale(2, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
        return s + suf[i];
    }

    // ================================================================== settings GUI
    static final class SettingsScreen extends Screen {
        private static final int PW = 250;
        private static final int PH = 200;
        private static final int SW = 22;   // swatch width
        private static final int GAP = 2;

        private int px, py, rowX;
        private ButtonWidget toggleButton;

        SettingsScreen() {
            super(Text.literal("DonutPay Settings"));
        }

        @Override
        protected void init() {
            px = (this.width - PW) / 2;
            py = (this.height - PH) / 2;
            int rowW = COLOR_VALUES.length * SW + (COLOR_VALUES.length - 1) * GAP;
            rowX = px + (PW - rowW) / 2;

            // color swatches (the colored squares are drawn on top in render())
            for (int i = 0; i < COLOR_VALUES.length; i++) {
                final int color = COLOR_VALUES[i];
                int x = rowX + i * (SW + GAP);
                this.addDrawableChild(ButtonWidget.builder(Text.empty(), b -> {
                    buttonColor = color;
                    saveConfig();
                }).dimensions(x, py + 38, SW, 20).build());
                this.addDrawableChild(ButtonWidget.builder(Text.empty(), b -> {
                    hudColor = color;
                    saveConfig();
                }).dimensions(x, py + 80, SW, 20).build());
            }

            this.addDrawableChild(ButtonWidget.builder(Text.literal("Edit HUD (move / resize)"),
                            b -> this.client.setScreen(new HudEditScreen(this)))
                    .dimensions(rowX, py + 112, rowW, 20).build());

            int half = (rowW - GAP * 2) / 2;
            toggleButton = ButtonWidget.builder(Text.literal(active ? "Stop session" : "Start session"), b -> {
                if (active) stopSession();
                else startSession();
                b.setMessage(Text.literal(active ? "Stop session" : "Start session"));
            }).dimensions(rowX, py + 138, half, 20).build();
            this.addDrawableChild(toggleButton);

            this.addDrawableChild(ButtonWidget.builder(Text.literal("Reset numbers"), b -> resetSession())
                    .dimensions(rowX + half + GAP * 2, py + 138, half, 20).build());

            this.addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> this.close())
                    .dimensions(rowX, py + 168, rowW, 20).build());
        }

        @Override
        public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
            super.renderBackground(ctx, mouseX, mouseY, delta);
            ctx.fill(px, py, px + PW, py + PH, 0xE0101010);
            ctx.drawStrokedRectangle(px, py, PW, PH, 0xFFAAAAAA);
        }

        @Override
        public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
            super.render(ctx, mouseX, mouseY, delta);

            ctx.drawCenteredTextWithShadow(this.textRenderer, "DonutPay Settings", this.width / 2, py + 10, 0xFFFFFFFF);
            ctx.drawText(this.textRenderer, "Button color: " + colorName(buttonColor), rowX, py + 26, 0xFFAAAAAA, false);
            ctx.drawText(this.textRenderer, "Profit HUD color: " + colorName(hudColor), rowX, py + 68, 0xFFAAAAAA, false);

            for (int i = 0; i < COLOR_VALUES.length; i++) {
                int x = rowX + i * (SW + GAP);
                drawSwatch(ctx, x, py + 38, COLOR_VALUES[i], buttonColor == COLOR_VALUES[i]);
                drawSwatch(ctx, x, py + 80, COLOR_VALUES[i], hudColor == COLOR_VALUES[i]);
            }
        }

        private void drawSwatch(DrawContext ctx, int x, int y, int color, boolean selected) {
            ctx.fill(x + 3, y + 3, x + SW - 3, y + 17, 0xFF000000 | color);
            if (selected) ctx.drawStrokedRectangle(x, y, SW, 20, 0xFFFFFFFF);
        }

        private static String colorName(int color) {
            for (int i = 0; i < COLOR_VALUES.length; i++) {
                if (COLOR_VALUES[i] == color) return COLOR_NAMES[i];
            }
            return "Custom";
        }

        @Override
        public boolean shouldPause() {
            return false;
        }
    }

    // ================================================================== HUD editor
    static final class HudEditScreen extends Screen {
        private final Screen parent;
        private boolean dragging = false;
        private boolean wasDown = false;
        private int grabX, grabY;

        HudEditScreen(Screen parent) {
            super(Text.literal("Edit HUD"));
            this.parent = parent;
        }

        @Override
        protected void init() {
            int by = this.height - 28;
            int bx = this.width / 2 - 101;
            this.addDrawableChild(ButtonWidget.builder(Text.literal("-"), b -> changeScale(-0.1f))
                    .dimensions(bx, by, 30, 20).build());
            this.addDrawableChild(ButtonWidget.builder(Text.literal("+"), b -> changeScale(0.1f))
                    .dimensions(bx + 34, by, 30, 20).build());
            this.addDrawableChild(ButtonWidget.builder(Text.literal("Reset"), b -> {
                hudX = 5;
                hudY = 40;
                hudScale = 1.0f;
                saveConfig();
            }).dimensions(bx + 68, by, 60, 20).build());
            this.addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> this.close())
                    .dimensions(bx + 132, by, 70, 20).build());
        }

        private void changeScale(float delta) {
            hudScale = Math.max(0.5f, Math.min(3.0f, Math.round((hudScale + delta) * 20f) / 20f));
            saveConfig();
        }

        // no dark/blurred background: we want to see the HUD and the game behind it
        @Override
        public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        }

        @Override
        public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
            MinecraftClient mc = MinecraftClient.getInstance();
            int w = hudWidth(mc.textRenderer);
            int h = hudHeight();

            // dragging: poll the left mouse button
            boolean down = GLFW.glfwGetMouseButton(GLFW.glfwGetCurrentContext(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
            boolean inside = mouseX >= hudX && mouseX <= hudX + w && mouseY >= hudY && mouseY <= hudY + h;
            if (down && !wasDown && inside && mouseY < this.height - 34) {
                dragging = true;
                grabX = mouseX - hudX;
                grabY = mouseY - hudY;
            }
            if (!down) {
                if (dragging) saveConfig();
                dragging = false;
            }
            if (dragging) {
                hudX = mouseX - grabX;
                hudY = mouseY - grabY;
            }
            wasDown = down;
            clampHud(mc, this.width, this.height);

            drawHud(ctx, mc);
            ctx.drawStrokedRectangle(hudX - 2, hudY - 2, w + 4, h + 4, 0xFFFFFFFF);

            ctx.drawCenteredTextWithShadow(this.textRenderer, "Drag the box to move it. Scroll wheel or +/- to resize.",
                    this.width / 2, this.height - 46, 0xFFFFFFFF);

            super.render(ctx, mouseX, mouseY, delta);
        }

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
            changeScale(verticalAmount > 0 ? 0.1f : -0.1f);
            return true;
        }

        @Override
        public void close() {
            saveConfig();
            this.client.setScreen(parent);
        }

        @Override
        public boolean shouldPause() {
            return false;
        }
    }
}
