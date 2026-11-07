package dev.bytefg.blmcapes;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Client-only cache and request scheduler for publicly assigned capes.
 * Network work is deliberately never initiated from a render callback.
 */
@Mod.EventBusSubscriber(modid = BlmCapes.MOD_ID, value = Dist.CLIENT)
public final class CapeSyncManager {
    private static final URI ENDPOINT = URI.create("https://bl-modern.ru/api/capes_public.php");
    private static final Set<String> ALLOWED_CAPES = Set.of("blm", "tfgm", "axolotl");
    private static final int MAX_BATCH_SIZE = 50;
    private static final int MAX_TRACKED_PLAYERS = 512;
    private static final int MAX_CACHE_ENTRIES = 1024;
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 10_000;
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private static final long CACHE_TTL_MS = 60 * 1_000L;
    private static final long FAILURE_RETRY_MS = 30 * 1_000L;

    private static final Map<UUID, CacheEntry> CACHE = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> IN_FLIGHT = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<BatchResult> PENDING_RESULTS = new ConcurrentLinkedQueue<>();
    private static final AtomicLong SESSION = new AtomicLong();
    private static int tickCounter;
    private static final ExecutorService REQUESTS = Executors.newSingleThreadExecutor(new DaemonThreadFactory());

    private CapeSyncManager() {}

    /** Returns a cached, validated cape id, or null while the player is unknown. */
    public static String getCape(UUID uuid) {
        if (uuid == null) return null;
        CacheEntry entry = CACHE.get(uuid);
        if (entry == null || entry.expiresAtMs < System.currentTimeMillis()) return null;
        return entry.capeId;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        applyPendingResults();

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.getCurrentServer() == null) return;
        String address = minecraft.getCurrentServer().ip.toLowerCase(java.util.Locale.ROOT);
        String host = address.split(":", 2)[0];
        if (!host.equals("play.bl-modern.ru") && !host.equals("tfgm2.bl-modern.ru")) return;
        if (++tickCounter < 20) return;
        tickCounter = 0;

        long now = System.currentTimeMillis();
        long session = SESSION.get();
        List<UUID> required = new ArrayList<>();
        for (AbstractClientPlayer player : minecraft.level.players()) {
            if (required.size() >= MAX_TRACKED_PLAYERS) break;
            UUID uuid = player.getUUID();
            CacheEntry cached = CACHE.get(uuid);
            if ((cached == null || cached.expiresAtMs < now) && IN_FLIGHT.putIfAbsent(uuid, session) == null) {
                required.add(uuid);
            }
        }

        for (int from = 0; from < required.size(); from += MAX_BATCH_SIZE) {
            List<UUID> batch = List.copyOf(required.subList(from, Math.min(from + MAX_BATCH_SIZE, required.size())));
            REQUESTS.execute(() -> requestBatch(session, batch));
        }
    }

    @SubscribeEvent
    public static void onDisconnect(ClientPlayerNetworkEvent.LoggingOut event) {
        SESSION.incrementAndGet();
        tickCounter = 0;
        CACHE.clear();
        IN_FLIGHT.clear();
        PENDING_RESULTS.clear();
    }

    private static void requestBatch(long session, List<UUID> requested) {
        if (SESSION.get() != session) {
            for (UUID uuid : requested) IN_FLIGHT.remove(uuid, session);
            return;
        }

        Map<UUID, String> capes = Collections.emptyMap();
        boolean successful = false;
        try {
            capes = fetchCapes(requested);
            successful = true;
        } catch (IOException | RuntimeException ignored) {
            // A failed public request must not affect client rendering or spam retries.
        } finally {
            for (UUID uuid : requested) IN_FLIGHT.remove(uuid, session);
        }

        if (SESSION.get() == session) {
            PENDING_RESULTS.add(new BatchResult(session, requested, capes, successful));
        }
    }

    private static Map<UUID, String> fetchCapes(List<UUID> uuids) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) ENDPOINT.toURL().openConnection();
        connection.setRequestMethod("POST");
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setDoOutput(true);
        byte[] body = requestBody(uuids);
        connection.setFixedLengthStreamingMode(body.length);
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        connection.setRequestProperty("Accept", "application/json");

        try {
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("Cape API returned HTTP " + connection.getResponseCode());
            }
            return parseResponse(readResponse(connection));
        } finally {
            connection.disconnect();
        }
    }

    private static byte[] requestBody(Collection<UUID> uuids) {
        StringBuilder json = new StringBuilder("{\"uuids\":[");
        boolean first = true;
        for (UUID uuid : uuids) {
            if (!first) json.append(',');
            json.append('\"').append(uuid).append('\"');
            first = false;
        }
        return json.append("]}").toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String readResponse(HttpURLConnection connection) throws IOException {
        try (BufferedInputStream input = new BufferedInputStream(connection.getInputStream());
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4_096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (output.size() + read > MAX_RESPONSE_BYTES) {
                    throw new IOException("Cape API response is too large");
                }
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8);
        }
    }

    private static Map<UUID, String> parseResponse(String json) throws IOException {
        JsonElement root = JsonParser.parseString(json);
        if (!root.isJsonObject()) throw new IOException("Cape API response is not an object");
        JsonObject response = root.getAsJsonObject();
        if (!response.has("success") || !response.get("success").getAsBoolean()) {
            throw new IOException("Cape API rejected request");
        }
        if (!response.has("capes") || !response.get("capes").isJsonObject()) return Map.of();

        Map<UUID, String> result = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : response.getAsJsonObject("capes").entrySet()) {
            try {
                UUID uuid = UUID.fromString(entry.getKey());
                String capeId = entry.getValue().getAsString();
                if (ALLOWED_CAPES.contains(capeId)) result.put(uuid, capeId);
            } catch (IllegalArgumentException | UnsupportedOperationException ignored) {
                // Ignore malformed, unexpected, and non-public values from the response.
            }
        }
        return result;
    }

    private static void applyPendingResults() {
        BatchResult batch;
        long now = System.currentTimeMillis();
        while ((batch = PENDING_RESULTS.poll()) != null) {
            if (batch.session != SESSION.get()) continue;
            long expiry = now + (batch.successful ? CACHE_TTL_MS : FAILURE_RETRY_MS);
            for (UUID uuid : batch.requested) {
                CACHE.put(uuid, new CacheEntry(batch.capes.get(uuid), expiry));
            }
        }
        trimCache();
    }

    private static void trimCache() {
        int excess = CACHE.size() - MAX_CACHE_ENTRIES;
        if (excess <= 0) return;
        for (UUID uuid : CACHE.keySet()) {
            if (excess-- <= 0) break;
            CACHE.remove(uuid);
        }
    }

    private record CacheEntry(String capeId, long expiresAtMs) {}
    private record BatchResult(long session, List<UUID> requested, Map<UUID, String> capes, boolean successful) {}

    private static final class DaemonThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "BLM Capes API");
            thread.setDaemon(true);
            return thread;
        }
    }
}
