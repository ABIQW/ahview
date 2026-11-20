package dev.ahview;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Shows Auction House info for ONE item at a time, only while the mouse is over it.
 * Nothing is downloaded when no item is hovered.
 */
public class AhViewMod implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("ahview");

    private static final String LIST_URL = "https://sky.coflnet.com/api/auctions/tag/%s/active/overview?orderBy=LOWEST_PRICE";
    private static final String SOLD_URL = "https://sky.coflnet.com/api/auctions/tag/%s/sold?page=0&pageSize=10";

    /** How many of the cheapest sellers are shown. */
    private static final int SHOW_SELLERS = 3;

    /** While hovering, download this item again every 10 seconds. */
    private static final long REFRESH_MS = 10_000;
    /** Items with no auctions at all are re-checked less often. */
    private static final long EMPTY_MS = 30_000;
    /** Safety: at most one item lookup (2 requests) per 2 seconds. */
    private static final long MIN_GAP_MS = 2_000;

    private enum State { LOADING, READY, ERROR }

    private record Offer(String name, double price) {}

    private record Info(State state, boolean sellable, List<Offer> offers,
                        double lastSold, long lastSoldAt) {}

    private static final class Entry {
        volatile Info info = new Info(State.LOADING, false, List.of(), 0, -1);
        volatile long fetchedAt = 0;
        volatile boolean inFlight = false;
    }

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ahview-fetch");
        t.setDaemon(true);
        return t;
    });

    private static final ConcurrentHashMap<String, Entry> CACHE = new ConcurrentHashMap<>();
    private static volatile long lastRequestAt = 0;

    @Override
    public void onInitializeClient() {
        ItemTooltipCallback.EVENT.register((stack, context, type, lines) -> {
            String key = keyOf(stack);
            if (key == null) return; // not an auction-style SkyBlock item

            Info info = get(key);
            switch (info.state()) {
                case LOADING -> lines.add(Component.literal("AH loading...").withStyle(ChatFormatting.GRAY));
                case ERROR -> lines.add(Component.literal("AH unavailable").withStyle(ChatFormatting.GRAY));
                case READY -> {
                    if (!info.sellable()) {
                        lines.add(Component.literal("AH: ").withStyle(ChatFormatting.GRAY)
                                .append(Component.literal("No").withStyle(ChatFormatting.RED)));
                    } else {
                        lines.add(Component.literal("AH: ").withStyle(ChatFormatting.GRAY)
                                .append(Component.literal("Yes").withStyle(ChatFormatting.GREEN)));

                        List<Offer> offers = info.offers();
                        if (offers.isEmpty()) {
                            lines.add(Component.literal("Nothing listed right now").withStyle(ChatFormatting.YELLOW));
                        } else {
                            lines.add(Component.literal("Lowest: ").withStyle(ChatFormatting.GRAY)
                                    .append(Component.literal(coins(offers.get(0).price()) + " coins")
                                            .withStyle(ChatFormatting.GOLD)));
                            int rank = 1;
                            for (Offer o : offers) {
                                lines.add(Component.literal(rank + ". ").withStyle(ChatFormatting.GRAY)
                                        .append(Component.literal(o.name()).withStyle(ChatFormatting.AQUA))
                                        .append(Component.literal(" - ").withStyle(ChatFormatting.GRAY))
                                        .append(Component.literal(coins(o.price()) + " coins")
                                                .withStyle(ChatFormatting.GOLD)));
                                rank++;
                            }
                        }

                        if (info.lastSold() > 0) {
                            String when = info.lastSoldAt() > 0 ? " (" + ago(info.lastSoldAt()) + ")" : "";
                            lines.add(Component.literal("Last sold: ").withStyle(ChatFormatting.GRAY)
                                    .append(Component.literal(coins(info.lastSold()) + " coins" + when)
                                            .withStyle(ChatFormatting.GOLD)));
                        }
                    }
                }
            }
        });
    }

    /** SkyBlock item id (reforge/stars ignored), or null for non-SkyBlock items, pets and enchanted books. */
    private static String keyOf(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return null;
        CompoundTag tag = data.copyTag();
        String id = tag.getStringOr("id", "");
        if (id.isEmpty() || id.equals("PET") || id.equals("ENCHANTED_BOOK")) return null;
        return id;
    }

    private static Info get(String id) {
        Entry e = CACHE.computeIfAbsent(id, k -> new Entry());
        long now = System.currentTimeMillis();
        Info cur = e.info;
        long maxAge = (cur.state() == State.READY && !cur.sellable()) ? EMPTY_MS : REFRESH_MS;

        if (!e.inFlight && now - e.fetchedAt >= maxAge && now - lastRequestAt >= MIN_GAP_MS) {
            e.inFlight = true;
            lastRequestAt = now;
            POOL.execute(() -> fetch(id, e));
        }
        return e.info;
    }

    private static void fetch(String id, Entry e) {
        try {
            String enc = URLEncoder.encode(id, StandardCharsets.UTF_8);
            JsonArray listed = getArray(String.format(LIST_URL, enc));
            JsonArray sold = getArray(String.format(SOLD_URL, enc));

            List<Offer> offers = new ArrayList<>();
            for (JsonElement el : listed) {
                if (!el.isJsonObject()) continue;
                JsonObject a = el.getAsJsonObject();
                double p = firstPositive(num(a, "price"),
                        firstPositive(num(a, "startingBid"), num(a, "highestBidAmount")));
                if (p <= 0) continue;
                String name = str(a, "playerName");
                if (name == null || name.isBlank()) name = "Unknown";
                offers.add(new Offer(name, p));
            }
            offers.sort(Comparator.comparingDouble(Offer::price));
            if (offers.size() > SHOW_SELLERS) {
                offers = new ArrayList<>(offers.subList(0, SHOW_SELLERS));
            }

            double lastPrice = 0;
            long lastAt = -1;
            for (JsonElement el : sold) {
                if (!el.isJsonObject()) continue;
                JsonObject a = el.getAsJsonObject();
                double p = firstPositive(num(a, "highestBidAmount"), num(a, "startingBid"));
                if (p <= 0) continue;
                double per = p / Math.max(1, (int) num(a, "count"));
                long t = parseTime(str(a, "end"));
                if (lastPrice == 0 || t > lastAt) {
                    lastPrice = per;
                    lastAt = t;
                }
            }

            boolean sellable = !offers.isEmpty() || lastPrice > 0;
            e.info = new Info(State.READY, sellable, offers, lastPrice, lastAt);
        } catch (Exception ex) {
            if (e.info.state() == State.LOADING) {
                e.info = new Info(State.ERROR, false, List.of(), 0, -1);
            }
            LOGGER.warn("AH lookup for {} failed: {}", id, ex.toString());
        } finally {
            e.fetchedAt = System.currentTimeMillis();
            e.inFlight = false;
        }
    }

    private static JsonArray getArray(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", "AhView-Mod/1.0")
                .GET()
                .build();
        HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        int code = res.statusCode();
        if (code == 404) return new JsonArray();
        if (code != 200) throw new IllegalStateException("HTTP " + code);
        JsonElement el = JsonParser.parseString(res.body());
        return el.isJsonArray() ? el.getAsJsonArray() : new JsonArray();
    }

    private static double num(JsonObject o, String key) {
        JsonElement v = o.get(key);
        return (v == null || v.isJsonNull()) ? 0 : v.getAsDouble();
    }

    private static String str(JsonObject o, String key) {
        JsonElement v = o.get(key);
        return (v == null || v.isJsonNull()) ? null : v.getAsString();
    }

    private static double firstPositive(double a, double b) {
        return a > 0 ? a : b;
    }

    private static long parseTime(String s) {
        if (s == null || s.isEmpty()) return -1;
        try {
            return Instant.parse(s).toEpochMilli();
        } catch (Exception ignored) {
            // try next format
        }
        try {
            return LocalDateTime.parse(s).toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (Exception ignored) {
            return -1;
        }
    }

    private static String ago(long at) {
        long s = Math.max(0, (System.currentTimeMillis() - at) / 1000);
        if (s < 60) return s + "s ago";
        if (s < 3600) return (s / 60) + "m ago";
        if (s < 86400) return (s / 3600) + "h ago";
        return (s / 86400) + "d ago";
    }

    private static String coins(double v) {
        if (v >= 1_000_000_000) return String.format(Locale.US, "%.2fB", v / 1_000_000_000);
        if (v >= 1_000_000) return String.format(Locale.US, "%.2fM", v / 1_000_000);
        if (v >= 1_000) return String.format(Locale.US, "%.1fK", v / 1_000);
        return String.format(Locale.US, "%.1f", v);
    }
}
