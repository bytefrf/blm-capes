package dev.bytefg.blmcapes;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

/**
 * Downloads custom cape bytes off-thread and performs GPU texture registration only from client ticks.
 * The server-controlled hash is never used as a URL except after strict protocol validation.
 */
final class CustomCapeTextures {
    private static final String TEXTURE_ENDPOINT = "https://bl-modern.ru/api/capes_texture.php?hash=";
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 10_000;
    private static final int MAX_TEXTURE_BYTES = 64 * 1024;
    private static final int MAX_CUSTOM_TEXTURES = 64;
    private static final byte[] PNG_SIGNATURE = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};

    private static final ThreadPoolExecutor DOWNLOADS = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(MAX_CUSTOM_TEXTURES),
            new DaemonThreadFactory(), new ThreadPoolExecutor.AbortPolicy());
    private static final Map<String, ManagedTexture> LOADED = new HashMap<>(); // Client thread only.
    private static final ConcurrentHashMap<String, Long> SCHEDULED = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> FAILED_UNTIL = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<DownloadResult> PENDING = new ConcurrentLinkedQueue<>();
    private static final Object PENDING_LOCK = new Object();
    private static final AtomicLong ACTIVE_SESSION = new AtomicLong(Long.MIN_VALUE);

    private CustomCapeTextures() {}

    static ResourceLocation getTexture(String hash) {
        ManagedTexture texture = LOADED.get(hash);
        return texture == null ? null : texture.location;
    }

    /** Must be called from the client thread. */
    static void synchronize(long session, Set<String> referencedHashes) {
        ACTIVE_SESSION.set(session);
        FAILED_UNTIL.keySet().removeIf(hash -> !referencedHashes.contains(hash));
        releaseUnreferenced(referencedHashes);
        applyDownloads(session, referencedHashes);
        scheduleReferenced(session, referencedHashes);
    }

    /** Must be called from the client thread after disconnect. */
    static void clear() {
        ACTIVE_SESSION.set(Long.MIN_VALUE);
        for (ManagedTexture texture : LOADED.values()) {
            Minecraft.getInstance().getTextureManager().release(texture.location);
        }
        LOADED.clear();
        SCHEDULED.clear();
        FAILED_UNTIL.clear();
        synchronized (PENDING_LOCK) {
            DownloadResult result;
            while ((result = PENDING.poll()) != null) result.image.close();
        }
    }

    private static void releaseUnreferenced(Set<String> referencedHashes) {
        LOADED.entrySet().removeIf(entry -> {
            if (referencedHashes.contains(entry.getKey())) return false;
            Minecraft.getInstance().getTextureManager().release(entry.getValue().location);
            return true;
        });
    }

    private static void applyDownloads(long session, Set<String> referencedHashes) {
        DownloadResult result;
        while ((result = PENDING.poll()) != null) {
            if (result.session != session || !referencedHashes.contains(result.hash)
                    || LOADED.containsKey(result.hash) || LOADED.size() >= MAX_CUSTOM_TEXTURES) {
                result.image.close();
                continue;
            }
            ResourceLocation location = new ResourceLocation(BlmCapes.MOD_ID, "custom_capes/" + result.hash);
            try {
                Minecraft.getInstance().getTextureManager().register(location, new DynamicTexture(result.image));
                LOADED.put(result.hash, new ManagedTexture(location));
            } catch (RuntimeException e) {
                result.image.close();
                BlmCapes.LOGGER.debug("Could not register custom cape texture {}", result.hash, e);
            }
        }
    }

    private static void scheduleReferenced(long session, Set<String> referencedHashes) {
        if (LOADED.size() >= MAX_CUSTOM_TEXTURES) return;
        List<String> hashes = new ArrayList<>(referencedHashes);
        hashes.sort(String::compareTo);
        long now = System.currentTimeMillis();
        for (String hash : hashes) {
            if (LOADED.containsKey(hash)) continue;
            if (LOADED.size() + scheduledFor(session) >= MAX_CUSTOM_TEXTURES) return;
            if (FAILED_UNTIL.getOrDefault(hash, 0L) > now) continue;
            Long previous = SCHEDULED.putIfAbsent(hash, session);
            if (previous != null) {
                if (previous == session) continue;
                SCHEDULED.put(hash, session);
            }
            try {
                DOWNLOADS.execute(() -> download(session, hash));
            } catch (RejectedExecutionException ignored) {
                SCHEDULED.remove(hash, session);
            }
        }
    }

    private static int scheduledFor(long session) {
        int count = 0;
        for (long scheduledSession : SCHEDULED.values()) {
            if (scheduledSession == session) count++;
        }
        return count;
    }

    private static void download(long session, String hash) {
        try {
            byte[] bytes = downloadBytes(hash);
            validatePngHeader(bytes);
            if (!hash.equals(sha256Hex(bytes))) throw new IOException("Custom cape SHA-256 mismatch");
            NativeImage image;
            try (ByteArrayInputStream input = new ByteArrayInputStream(bytes)) {
                image = NativeImage.read(input);
            }
            if (image.getWidth() != 64 || image.getHeight() != 32) {
                image.close();
                throw new IOException("Custom cape dimensions are not 64x32");
            }
            synchronized (PENDING_LOCK) {
                if (ACTIVE_SESSION.get() != session) {
                    image.close();
                    return;
                }
                PENDING.add(new DownloadResult(session, hash, image));
            }
        } catch (IOException | RuntimeException e) {
            if (ACTIVE_SESSION.get() == session) {
                FAILED_UNTIL.put(hash, System.currentTimeMillis() + 30_000L);
            }
            BlmCapes.LOGGER.debug("Could not download custom cape {}: {}", hash, e.getMessage());
        } finally {
            SCHEDULED.remove(hash, session);
        }
    }

    private static byte[] downloadBytes(String hash) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(TEXTURE_ENDPOINT + hash).toURL().openConnection();
        connection.setRequestMethod("GET");
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestProperty("Accept", "image/png");
        try {
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("Cape texture returned HTTP " + connection.getResponseCode());
            }
            try (BufferedInputStream input = new BufferedInputStream(connection.getInputStream());
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4_096];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (output.size() + read > MAX_TEXTURE_BYTES) throw new IOException("Custom cape is too large");
                    output.write(buffer, 0, read);
                }
                return output.toByteArray();
            }
        } finally {
            connection.disconnect();
        }
    }

    /** Validates IHDR before native decoding to avoid a compressed image bomb allocating a large bitmap. */
    static void validatePngHeader(byte[] bytes) throws IOException {
        if (bytes.length < 33) throw new IOException("Custom cape is not a complete PNG");
        for (int i = 0; i < PNG_SIGNATURE.length; i++) {
            if (bytes[i] != PNG_SIGNATURE[i]) throw new IOException("Custom cape is not PNG");
        }
        if (bytes[12] != 'I' || bytes[13] != 'H' || bytes[14] != 'D' || bytes[15] != 'R') {
            throw new IOException("Custom cape lacks PNG IHDR");
        }
        int width = readInt(bytes, 16);
        int height = readInt(bytes, 20);
        if (width != 64 || height != 32) throw new IOException("Custom cape dimensions are not 64x32");
    }

    private static int readInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) << 24 | (bytes[offset + 1] & 0xff) << 16
                | (bytes[offset + 2] & 0xff) << 8 | bytes[offset + 3] & 0xff;
    }

    static String sha256Hex(byte[] bytes) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest) hex.append(String.format("%02x", value & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is unavailable", e);
        }
    }

    private record ManagedTexture(ResourceLocation location) {}
    private record DownloadResult(long session, String hash, NativeImage image) {}

    private static final class DaemonThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "BLM Capes texture downloader");
            thread.setDaemon(true);
            return thread;
        }
    }
}
