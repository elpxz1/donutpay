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
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DonutPay
 *  - Orange [2x] [Bal] [Payback] buttons when someone pays you.
 *  - /dpay            opens the settings GUI (General tab + Top Gamblers tab)
 *  - /dpay start|stop starts / ends a profit-tracking session (the panels only show during a session)
 *  - Top Gamblers panel = who paid you the most during the current session
 *  - Nothing is ever sent automatically: every payment needs a click from you.
 */
public class DonutPayClient implements ClientModInitializer {

    // ------------------------------------------------------------------ colors
    static final String[] COLOR_NAMES = {"Orange", "Red", "Yellow", "Green", "Aqua", "Blue", "Purple", "Pink", "White"};
    static final int[] COLOR_VALUES = {0xFFA500, 0xFF5555, 0xFFFF55, 0x55FF55, 0x55FFFF, 0x5555FF, 0xAA55FF, 0xFF55FF, 0xFFFFFF};

    // ------------------------------------------------------------------ HUD panels
    /** Position / size / color of one on-screen panel. */
    static final class PanelCfg {
        final int defX, defY;
        int x, y, color;
        float scale = 1.0f;

        PanelCfg(int x, int y, int color) {
            this.defX = x;
            this.defY = y;
            this.x = x;
            this.y = y;
            this.color = color;
        }

        void resetPosition() {
            x = defX;
            y = defY;
            scale = 1.0f;
        }
    }

    static final PanelCfg PROFIT = new PanelCfg(5, 40, 0xFFA500);
    static final PanelCfg GAMBLERS = new PanelCfg(5, 105, 0xFFA500);

    // ------------------------------------------------------------------ saved settings
    static int buttonColor = 0xFFA500;
    static boolean gamblersEnabled = true;
    static int gamblerCount = 5;

    // ------------------------------------------------------------------ session
    static boolean active = false;
    static long startMs = 0;
    static long received = 0;
    static long paidOut = 0;
    static long taxPaid = 0;
    static int countIn = 0;
    static int countOut = 0;
    /** player name -> total they paid you during this session */
    static final Map<String, Long> gamblers = new HashMap<>();

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

        // HUD panels
        HudRenderCallback.EVENT.register((ctx, tickCounter) -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.options.hudHidden) return;
            if (!active) return;
            // the editor draws the panel it is editing by itself
            PanelCfg editing = mc.currentScreen instanceof HudEditScreen e ? e.panel : null;
            if (editing != PROFIT) drawPanel(ctx, mc, PROFIT, false);
            if (gamblersEnabled && editing != GAMBLERS) drawPanel(ctx, mc, GAMBLERS, false);
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
                        .then(ClientCommandManager.literal("gamblers").executes(ctx -> {
                            gamblersEnabled = !gamblersEnabled;
                            saveConfig();
                            info("Top Gamblers panel " + (gamblersEnabled ? "ON" : "OFF"));
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
            final String shown = s;
            String low = s.toLowerCase();
            if (low.contains("paid") || low.contains("pay") || low.contains("sent") || low.contains("$")) {
                MinecraftClient.getInstance().execute(() -> info("RAW [" + source + "]: " + shown));
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
        String name = m.group(1);
        if (active) {
            received += amount;
            countIn++;
            gamblers.merge(name, amount, Long::sum);
        }
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
        String top = "";
        List<Map.Entry<String, Long>> list = topGamblers(1);
        if (!list.isEmpty()) {
            top = " Top gambler: " + list.get(0).getKey() + " ($" + compact(list.get(0).getValue()) + ").";
        }
        info("Session ended: received $" + compact(received) + ", paid out $" + compact(paidOut + taxPaid)
                + ", profit " + signedMoney(profit()) + " in " + elapsedText() + "." + top);
    }

    static void resetSession() {
        received = 0;
        paidOut = 0;
        taxPaid = 0;
        countIn = 0;
        countOut = 0;
        gamblers.clear();
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

    /** The players who paid you the most this session, biggest first. */
    static List<Map.Entry<String, Long>> topGamblers(int limit) {
        List<Map.Entry<String, Long>> list = new ArrayList<>(gamblers.entrySet());
        list.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        if (list.size() > limit) list = new ArrayList<>(list.subList(0, limit));
        return list;
    }

    // ================================================================== HUD drawing
    private static final int PAD = 4;
    private static final int LINE_H = 10;

    private static String[] panelLines(PanelCfg p, boolean preview) {
        if (p == PROFIT) {
            return new String[]{
                    "DonutPay Profit",
                    "Received: $" + compact(received) + " (" + countIn + ")",
                    "Paid out: $" + compact(paidOut + taxPaid) + " (" + countOut + ")",
                    "Profit: " + signedMoney(profit()),
                    "Time: " + elapsedText()
            };
        }
        List<Map.Entry<String, Long>> top = topGamblers(gamblerCount);
        if (top.isEmpty() && preview) {
            top = new ArrayList<>();
            top.add(new AbstractMap.SimpleEntry<>("Steve", 50000L));
            top.add(new AbstractMap.SimpleEntry<>("Alex", 20000L));
            top.add(new AbstractMap.SimpleEntry<>("Notch", 5000L));
        }
        if (top.isEmpty()) {
            return new String[]{"Top Gamblers", "No payments yet"};
        }
        String[] out = new String[top.size() + 1];
        out[0] = "Top Gamblers";
        for (int i = 0; i < top.size(); i++) {
            out[i + 1] = (i + 1) + ". " + top.get(i).getKey() + "  $" + compact(top.get(i).getValue());
        }
        return out;
    }

    private static int[] panelColors(PanelCfg p, String[] lines) {
        int[] c = new int[lines.length];
        if (p == PROFIT) {
            c[0] = p.color;
            c[1] = 0x55FF55;
            c[2] = 0xFF5555;
            c[3] = profit() >= 0 ? 0x55FF55 : 0xFF5555;
            c[4] = 0xAAAAAA;
            return c;
        }
        c[0] = p.color;
        for (int i = 1; i < c.length; i++) c[i] = 0xFFFFFF;
        if (lines.length == 2 && lines[1].equals("No payments yet")) c[1] = 0xAAAAAA;
        return c;
    }

    static int panelBaseWidth(TextRenderer tr, String[] lines) {
        int w = 0;
        for (String l : lines) w = Math.max(w, tr.getWidth(l));
        return w + PAD * 2;
    }

    static int panelBaseHeight(String[] lines) {
        return lines.length * LINE_H + PAD * 2 - 1;
    }

    static int panelWidth(MinecraftClient mc, PanelCfg p, boolean preview) {
        return Math.round(panelBaseWidth(mc.textRenderer, panelLines(p, preview)) * p.scale);
    }

    static int panelHeight(PanelCfg p, boolean preview) {
        return Math.round(panelBaseHeight(panelLines(p, preview)) * p.scale);
    }

    static void clampPanel(MinecraftClient mc, PanelCfg p, int screenW, int screenH, boolean preview) {
        int w = panelWidth(mc, p, preview);
        int h = panelHeight(p, preview);
        p.x = Math.max(0, Math.min(p.x, Math.max(0, screenW - w)));
        p.y = Math.max(0, Math.min(p.y, Math.max(0, screenH - h)));
    }

    static void drawPanel(DrawContext ctx, MinecraftClient mc, PanelCfg p, boolean preview) {
        TextRenderer tr = mc.textRenderer;
        clampPanel(mc, p, ctx.getScaledWindowWidth(), ctx.getScaledWindowHeight(), preview);

        String[] lines = panelLines(p, preview);
        int[] colors = panelColors(p, lines);
        int w = panelBaseWidth(tr, lines);
        int h = panelBaseHeight(lines);

        var m = ctx.getMatrices();
        m.pushMatrix();
        m.translate((float) p.x, (float) p.y);
        m.scale(p.scale, p.scale);

        ctx.fill(0, 0, w, h, 0xB0000000);
        ctx.drawStrokedRectangle(0, 0, w, h, 0xFF000000 | p.color);
        for (int i = 0; i < lines.length; i++) {
            ctx.drawText(tr, lines[i], PAD, PAD + i * LINE_H, 0xFF000000 | colors[i], true);
        }
        m.popMatrix();
    }

    // ================================================================== config file
    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("donutpay.json");
    }

    private static float clampScale(float s) {
        return Math.max(0.5f, Math.min(3.0f, s));
    }

    static void loadConfig() {
        try {
            Path p = configPath();
            if (!Files.exists(p)) return;
            JsonObject o = JsonParser.parseString(Files.readString(p)).getAsJsonObject();
            if (o.has("buttonColor")) buttonColor = o.get("buttonColor").getAsInt();
            if (o.has("hudColor")) PROFIT.color = o.get("hudColor").getAsInt();
            if (o.has("hudX")) PROFIT.x = o.get("hudX").getAsInt();
            if (o.has("hudY")) PROFIT.y = o.get("hudY").getAsInt();
            if (o.has("hudScale")) PROFIT.scale = clampScale(o.get("hudScale").getAsFloat());
            if (o.has("gamblersEnabled")) gamblersEnabled = o.get("gamblersEnabled").getAsBoolean();
            if (o.has("gamblerColor")) GAMBLERS.color = o.get("gamblerColor").getAsInt();
            if (o.has("gamblerX")) GAMBLERS.x = o.get("gamblerX").getAsInt();
            if (o.has("gamblerY")) GAMBLERS.y = o.get("gamblerY").getAsInt();
            if (o.has("gamblerScale")) GAMBLERS.scale = clampScale(o.get("gamblerScale").getAsFloat());
            if (o.has("gamblerCount")) gamblerCount = Math.max(1, Math.min(10, o.get("gamblerCount").getAsInt()));
        } catch (Exception ignored) {
            // bad or missing config: just use defaults
        }
    }

    static void saveConfig() {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("buttonColor", buttonColor);
            o.addProperty("hudColor", PROFIT.color);
            o.addProperty("hudX", PROFIT.x);
            o.addProperty("hudY", PROFIT.y);
            o.addProperty("hudScale", PROFIT.scale);
            o.addProperty("gamblersEnabled", gamblersEnabled);
            o.addProperty("gamblerColor", GAMBLERS.color);
            o.addProperty("gamblerX", GAMBLERS.x);
            o.addProperty("gamblerY", GAMBLERS.y);
            o.addProperty("gamblerScale", GAMBLERS.scale);
            o.addProperty("gamblerCount", gamblerCount);
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
        private static final int PH = 222;
        private static final int SW = 22;   // swatch width
        private static final int GAP = 2;

        private int px, py, rowX, rowW;
        private int tab = 0; // 0 = General, 1 = Top Gamblers
        private final List<ButtonWidget> generalWidgets = new ArrayList<>();
        private final List<ButtonWidget> gamblerWidgets = new ArrayList<>();
        private ButtonWidget gamblerToggle;

        SettingsScreen() {
            super(Text.literal("DonutPay Settings"));
        }

        private ButtonWidget add(List<ButtonWidget> list, ButtonWidget w) {
            this.addDrawableChild(w);
            list.add(w);
            return w;
        }

        @Override
        protected void init() {
            generalWidgets.clear();
            gamblerWidgets.clear();
            px = (this.width - PW) / 2;
            py = (this.height - PH) / 2;
            rowW = COLOR_VALUES.length * SW + (COLOR_VALUES.length - 1) * GAP;
            rowX = px + (PW - rowW) / 2;
            int half = (rowW - GAP * 2) / 2;

            // ---- tab buttons (always visible)
            this.addDrawableChild(ButtonWidget.builder(Text.literal("General"), b -> {
                tab = 0;
                applyTab();
            }).dimensions(rowX, py + 8, half, 18).build());
            this.addDrawableChild(ButtonWidget.builder(Text.literal("Top Gamblers"), b -> {
                tab = 1;
                applyTab();
            }).dimensions(rowX + half + GAP * 2, py + 8, half, 18).build());

            // ---- General tab
            for (int i = 0; i < COLOR_VALUES.length; i++) {
                final int color = COLOR_VALUES[i];
                int x = rowX + i * (SW + GAP);
                add(generalWidgets, ButtonWidget.builder(Text.empty(), b -> {
                    buttonColor = color;
                    saveConfig();
                }).dimensions(x, py + 46, SW, 20).build());
                add(generalWidgets, ButtonWidget.builder(Text.empty(), b -> {
                    PROFIT.color = color;
                    saveConfig();
                }).dimensions(x, py + 88, SW, 20).build());
            }
            add(generalWidgets, ButtonWidget.builder(Text.literal("Edit profit HUD (move / resize)"),
                            b -> this.client.setScreen(new HudEditScreen(this, PROFIT)))
                    .dimensions(rowX, py + 120, rowW, 20).build());
            add(generalWidgets, ButtonWidget.builder(Text.literal(active ? "Stop session" : "Start session"), b -> {
                if (active) stopSession();
                else startSession();
                b.setMessage(Text.literal(active ? "Stop session" : "Start session"));
            }).dimensions(rowX, py + 146, half, 20).build());
            add(generalWidgets, ButtonWidget.builder(Text.literal("Reset numbers"), b -> resetSession())
                    .dimensions(rowX + half + GAP * 2, py + 146, half, 20).build());

            // ---- Top Gamblers tab
            gamblerToggle = add(gamblerWidgets, ButtonWidget.builder(gamblerToggleText(), b -> {
                gamblersEnabled = !gamblersEnabled;
                saveConfig();
                b.setMessage(gamblerToggleText());
            }).dimensions(rowX, py + 34, rowW, 20).build());
            for (int i = 0; i < COLOR_VALUES.length; i++) {
                final int color = COLOR_VALUES[i];
                int x = rowX + i * (SW + GAP);
                add(gamblerWidgets, ButtonWidget.builder(Text.empty(), b -> {
                    GAMBLERS.color = color;
                    saveConfig();
                }).dimensions(x, py + 74, SW, 20).build());
            }
            add(gamblerWidgets, ButtonWidget.builder(Text.literal("-"), b -> {
                gamblerCount = Math.max(1, gamblerCount - 1);
                saveConfig();
            }).dimensions(rowX, py + 104, 30, 20).build());
            add(gamblerWidgets, ButtonWidget.builder(Text.literal("+"), b -> {
                gamblerCount = Math.min(10, gamblerCount + 1);
                saveConfig();
            }).dimensions(rowX + rowW - 30, py + 104, 30, 20).build());
            add(gamblerWidgets, ButtonWidget.builder(Text.literal("Edit list (move / resize)"),
                            b -> this.client.setScreen(new HudEditScreen(this, GAMBLERS)))
                    .dimensions(rowX, py + 132, rowW, 20).build());

            // ---- Done (always visible)
            this.addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> this.close())
                    .dimensions(rowX, py + 194, rowW, 20).build());

            applyTab();
        }

        private Text gamblerToggleText() {
            return Text.literal("Top Gamblers panel: " + (gamblersEnabled ? "ON" : "OFF"));
        }

        private void applyTab() {
            for (ButtonWidget w : generalWidgets) {
                w.visible = tab == 0;
                w.active = tab == 0;
            }
            for (ButtonWidget w : gamblerWidgets) {
                w.visible = tab == 1;
                w.active = tab == 1;
            }
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

            // highlight the active tab
            int half = (rowW - GAP * 2) / 2;
            int tabX = tab == 0 ? rowX : rowX + half + GAP * 2;
            ctx.drawStrokedRectangle(tabX, py + 8, half, 18, 0xFFFFFFFF);

            if (tab == 0) {
                ctx.drawText(this.textRenderer, "Button color: " + colorName(buttonColor), rowX, py + 34, 0xFFAAAAAA, false);
                ctx.drawText(this.textRenderer, "Profit HUD color: " + colorName(PROFIT.color), rowX, py + 76, 0xFFAAAAAA, false);
                for (int i = 0; i < COLOR_VALUES.length; i++) {
                    int x = rowX + i * (SW + GAP);
                    drawSwatch(ctx, x, py + 46, COLOR_VALUES[i], buttonColor == COLOR_VALUES[i]);
                    drawSwatch(ctx, x, py + 88, COLOR_VALUES[i], PROFIT.color == COLOR_VALUES[i]);
                }
            } else {
                ctx.drawText(this.textRenderer, "List color: " + colorName(GAMBLERS.color), rowX, py + 62, 0xFFAAAAAA, false);
                for (int i = 0; i < COLOR_VALUES.length; i++) {
                    int x = rowX + i * (SW + GAP);
                    drawSwatch(ctx, x, py + 74, COLOR_VALUES[i], GAMBLERS.color == COLOR_VALUES[i]);
                }
                ctx.drawCenteredTextWithShadow(this.textRenderer, "Show top " + gamblerCount,
                        this.width / 2, py + 110, 0xFFFFFFFF);
                ctx.drawCenteredTextWithShadow(this.textRenderer, "Who paid you the most this session",
                        this.width / 2, py + 160, 0xFF888888);
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
        final PanelCfg panel;
        private boolean dragging = false;
        private boolean wasDown = false;
        private int grabX, grabY;

        HudEditScreen(Screen parent, PanelCfg panel) {
            super(Text.literal("Edit HUD"));
            this.parent = parent;
            this.panel = panel;
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
                panel.resetPosition();
                saveConfig();
            }).dimensions(bx + 68, by, 60, 20).build());
            this.addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> this.close())
                    .dimensions(bx + 132, by, 70, 20).build());
        }

        private void changeScale(float delta) {
            panel.scale = clampScale(Math.round((panel.scale + delta) * 20f) / 20f);
            saveConfig();
        }

        // no dark/blurred background: we want to see the panel and the game behind it
        @Override
        public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        }

        @Override
        public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
            MinecraftClient mc = MinecraftClient.getInstance();
            int w = panelWidth(mc, panel, true);
            int h = panelHeight(panel, true);

            // dragging: poll the left mouse button
            boolean down = GLFW.glfwGetMouseButton(GLFW.glfwGetCurrentContext(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
            boolean inside = mouseX >= panel.x && mouseX <= panel.x + w && mouseY >= panel.y && mouseY <= panel.y + h;
            if (down && !wasDown && inside && mouseY < this.height - 34) {
                dragging = true;
                grabX = mouseX - panel.x;
                grabY = mouseY - panel.y;
            }
            if (!down) {
                if (dragging) saveConfig();
                dragging = false;
            }
            if (dragging) {
                panel.x = mouseX - grabX;
                panel.y = mouseY - grabY;
            }
            wasDown = down;
            clampPanel(mc, panel, this.width, this.height, true);

            drawPanel(ctx, mc, panel, true);
            ctx.drawStrokedRectangle(panel.x - 2, panel.y - 2, w + 4, h + 4, 0xFFFFFFFF);

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
