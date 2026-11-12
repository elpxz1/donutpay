package dev.donutpay;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.util.Formatting;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DonutPay - when someone pays you on DonutSMP, an orange button row shows up in chat:
 *   [2x]      -> pays them back double what they sent
 *   [Bal]     -> runs /bal <their name>
 *   [Payback] -> pays them back exactly what they sent
 * Nothing is ever sent automatically: every action needs a click from you.
 */
public class DonutPayClient implements ClientModInitializer {

    private static final int ORANGE = 0xFFA500;

    // "Steve paid you $200K"  (whole line must match, so players can't fake it by typing it mid-sentence)
    private static final Pattern PAID = Pattern.compile(
            "^(\\w{3,16}) (?:has )?(?:paid|sent) you \\$?([\\d,]*\\.?\\d+)\\s*([kKmMbBtT]?)\\.?!?$");

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

    @Override
    public void onInitializeClient() {
        // GAME = server/system messages only. Player chat is ignored on purpose (spoof protection).
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay) return;
            handle(message.getString());
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("dpay")
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
                                            })))));
        });
    }

    private static void handle(String raw) {
        String s = Formatting.strip(raw);
        if (s == null) return;
        Matcher m = PAID.matcher(s.trim());
        if (!m.matches()) return;

        long amount = parseAmount(m.group(2), m.group(3));
        if (amount <= 0) return;
        MinecraftClient.getInstance().execute(() -> show(m.group(1), amount));
    }

    private static void show(String name, long amount) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.inGameHud == null) return;

        int id = nextId++;
        PAYMENTS.put(id, new Payment(name, amount));

        long doubled = amount * 2;
        MutableText line = Text.literal("» ").setStyle(Style.EMPTY.withColor(TextColor.fromRgb(ORANGE)));
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
                .withColor(TextColor.fromRgb(ORANGE))
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

    private static void info(String msg) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.inGameHud != null) {
            mc.inGameHud.getChatHud().addMessage(Text.literal("[DonutPay] " + msg)
                    .setStyle(Style.EMPTY.withColor(TextColor.fromRgb(ORANGE))));
        }
    }

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
    private static String compact(long n) {
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
}
