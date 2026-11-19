package dev.ahview;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * AhView: Auction House info in SkyBlock item tooltips.
 *  - Sellable on AH: Yes / No
 *  - Lowest BIN and Highest BIN right now
 *  - Last sold price (and how long ago)
 */
public class AhViewMod implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("ahview");

    private static final String AUCTIONS_URL = "https://api.hypixel.net/v2/skyblock/auctions?page=";
    private static final String ENDED_URL = "https://api.hypixel.net/v2/skyblock/auctions_ended";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** Current BIN listings for one item. */
    private record Listing(double lowest, double highest, int count) {}

    /** Last time this item was seen sold via BIN. */
    private record Sale(double price, long timeMillis) {}

    private static volatile Map<String, Listing> listings = Map.of();
    private static volatile boolean listingsLoaded = false;
    private static final Map<String, Sale> lastSold = new ConcurrentHashMap<>();
    private static Path saveFile;

    // =====================================================================
    // Startup + tooltip
    // =====================================================================

    @Override
    public void onInitializeClient() {
        saveFile = FabricLoader.getInstance().getConfigDir().resolve("ahview-lastsold.json");
        loadSaved();

        ScheduledExecutorService ex = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "ahview-refresh");
            t.setDaemon(true);
            return t;
        });
        // The AH feed itself only updates about once a minute.
        ex.scheduleWithFixedDelay(AhViewMod::refreshListings, 0, 120, TimeUnit.SECONDS);
        // auctions_ended only holds roughly the last minute of sales, so poll every 60s.
        ex.scheduleWithFixedDelay(AhViewMod::refreshSold, 5, 60, TimeUnit.SECONDS);

        ItemTooltipCallback.EVENT.register((stack, context, type, lines) -> {
            String key = itemKey(stack);
            if (key == null) return; // not a SkyBlock item

            if (!listingsLoaded && lastSold.isEmpty()) {
                lines.add(Component.literal("AH prices loading...").withStyle(ChatFormatting.GRAY));
                return;
            }

            Listing l = listings.get(key);
            Sale s = lastSold.get(key);

            if (l == null && s == null) {
                lines.add(Component.literal("Sellable on AH: ").withStyle(ChatFormatting.GRAY)
                        .append(Component.literal("No").withStyle(ChatFormatting.RED))
                        .append(Component.literal(" (nothing listed or sold)")
                                .withStyle(ChatFormatting.DARK_GRAY)));
                return;
            }

            lines.add(Component.literal("Sellable on AH: ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal("Yes").withStyle(ChatFormatting.GREEN)));

            if (l != null) {
                lines.add(priceLine("Lowest BIN", l.lowest()));
                lines.add(priceLine("Highest BIN", l.highest()));
                lines.add(Component.literal("Listed now: " + l.count())
                        .withStyle(ChatFormatting.DARK_GRAY));
            } else {
                lines.add(Component.literal("No BIN listed right now").withStyle(ChatFormatting.YELLOW));
            }

            if (s != null) {
                lines.add(Component.literal("Last sold: ").withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(coins(s.price()) + " coins").withStyle(ChatFormatting.GOLD))
                        .append(Component.literal(" (" + ago(s.timeMillis()) + ")")
                                .withStyle(ChatFormatting.DARK_GRAY)));
            } else {
                lines.add(Component.literal("Last sold: not seen yet").withStyle(ChatFormatting.DARK_GRAY));
            }
        });
    }

    // =====================================================================
    // Item key (so an in-game item matches an auction's item)
    // =====================================================================

    private static String itemKey(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return null;
        return keyFromTag(data.copyTag());
    }

    private static String keyFromTag(CompoundTag tag) {
        String id = tag.getStringOr("id", "");
        if (id.isEmpty()) return null;

        if (id.equals("ENCHANTED_BOOK")) {
            CompoundTag ench = tag.getCompoundOrEmpty("enchantments");
            if (ench.keySet().size() == 1) {
                String name = ench.keySet().iterator().next();
                return "ENCHANTMENT_" + name.toUpperCase(Locale.ROOT) + "_" + ench.getIntOr(name, 0);
            }
            return id;
        }

        if (id.equals("PET")) {
            try {
                JsonObject info = JsonParser.parseString(tag.getStringOr("petInfo", "{}")).getAsJsonObject();
                String type = info.get("type").getAsString();
                String tier = info.get("tier").getAsString();
                return type + ";" + tier;
            } catch (Exception ignored) {
                // fall through
            }
        }
        return id;
    }

    /** item_bytes from the auction API = base64( gzipped NBT in the old 1.8 layout ). */
    private static String keyFromItemBytes(JsonElement el) {
        try {
            String b64 = el.isJsonObject() ? el.getAsJsonObject().get("data").getAsString() : el.getAsString();
            byte[] raw = Base64.getDecoder().decode(b64);
            CompoundTag root = NbtIo.readCompressed(new ByteArrayInputStream(raw), NbtAccounter.unlimitedHeap());
            CompoundTag extra = root.getListOrEmpty("i")
                    .getCompoundOrEmpty(0)
                    .getCompoundOrEmpty("tag")
                    .getCompoundOrEmpty("ExtraAttributes");
            return keyFromTag(extra);
        } catch (Exception e) {
            return null;
        }
    }

    // =====================================================================
    // Data: active BINs
    // =====================================================================

    private static void refreshListings() {
        try {
            Map<String, double[]> acc = new HashMap<>(); // id -> {low, high, count}
            JsonObject first = JsonParser.parseString(fetch(AUCTIONS_URL + 0)).getAsJsonObject();
            int totalPages = first.get("totalPages").getAsInt();
            readAuctionPage(first, acc);

            for (int page = 1; page < totalPages; page++) {
                readAuctionPage(JsonParser.parseString(fetch(AUCTIONS_URL + page)).getAsJsonObject(), acc);
                Thread.sleep(100); // be gentle with the API
            }

            Map<String, Listing> result = new HashMap<>();
            for (Map.Entry<String, double[]> e : acc.entrySet()) {
                double[] v = e.getValue();
                result.put(e.getKey(), new Listing(v[0], v[1], (int) v[2]));
            }
            listings = result;
            listingsLoaded = true;
        } catch (Exception ex) {
            LOGGER.warn("AH refresh failed: {}", ex.toString());
        }
    }

    private static void readAuctionPage(JsonObject page, Map<String, double[]> acc) {
        for (JsonElement el : page.getAsJsonArray("auctions")) {
            JsonObject a = el.getAsJsonObject();
            if (!a.has("bin") || !a.get("bin").getAsBoolean()) continue;
            if (a.has("claimed") && a.get("claimed").getAsBoolean()) continue;

            String id = keyFromItemBytes(a.get("item_bytes"));
            if (id == null) continue;

            double price = a.get("starting_bid").getAsDouble();
            double[] cur = acc.get(id);
            if (cur == null) {
                acc.put(id, new double[]{price, price, 1});
            } else {
                cur[0] = Math.min(cur[0], price);
                cur[1] = Math.max(cur[1], price);
                cur[2] += 1;
            }
        }
    }

    // =====================================================================
    // Data: sold auctions (saved between sessions)
    // =====================================================================

    private static void refreshSold() {
        try {
            JsonObject root = JsonParser.parseString(fetch(ENDED_URL)).getAsJsonObject();
            boolean changed = false;
            for (JsonElement el : root.getAsJsonArray("auctions")) {
                JsonObject a = el.getAsJsonObject();
                if (!a.has("bin") || !a.get("bin").getAsBoolean()) continue;

                String id = keyFromItemBytes(a.get("item_bytes"));
                if (id == null) continue;

                double price = a.get("price").getAsDouble();
                long time = a.get("timestamp").getAsLong();

                Sale old = lastSold.get(id);
                if (old == null || time > old.timeMillis()) {
                    lastSold.put(id, new Sale(price, time));
                    changed = true;
                }
            }
            if (changed) save();
        } catch (Exception ex) {
            LOGGER.warn("Sold refresh failed: {}", ex.toString());
        }
    }

    private static void loadSaved() {
        try {
            if (Files.exists(saveFile)) {
                JsonObject root = JsonParser.parseString(Files.readString(saveFile)).getAsJsonObject();
                for (Map.Entry<String, JsonElement> e : root.entrySet()) {
                    JsonObject o = e.getValue().getAsJsonObject();
                    lastSold.put(e.getKey(), new Sale(o.get("p").getAsDouble(), o.get("t").getAsLong()));
                }
            }
        } catch (Exception ex) {
            LOGGER.warn("Could not read saved sales: {}", ex.toString());
        }
    }

    private static synchronized void save() {
        try {
            JsonObject root = new JsonObject();
            for (Map.Entry<String, Sale> e : lastSold.entrySet()) {
                JsonObject o = new JsonObject();
                o.addProperty("p", e.getValue().price());
                o.addProperty("t", e.getValue().timeMillis());
                root.add(e.getKey(), o);
            }
            Files.writeString(saveFile, root.toString());
        } catch (Exception ex) {
            LOGGER.warn("Could not save sales: {}", ex.toString());
        }
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private static String fetch(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "AhView-Mod/1.0")
                .GET()
                .build();
        HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + res.statusCode() + " from " + url);
        }
        return res.body();
    }

    private static Component priceLine(String label, double price) {
        return Component.literal(label + ": ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(coins(price) + " coins").withStyle(ChatFormatting.GOLD));
    }

    private static String ago(long timeMillis) {
        long s = Math.max(0, (System.currentTimeMillis() - timeMillis) / 1000);
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
