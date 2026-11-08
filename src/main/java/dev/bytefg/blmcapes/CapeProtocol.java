package dev.bytefg.blmcapes;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Validates the public v2 protocol without accepting arbitrary resource locations. */
final class CapeProtocol {
    static final Set<String> BUILTIN_IDS = Set.of(
            "blm", "tfgm", "axolotl", "copper", "bronze", "steel", "space", "forest",
            "flame", "ice", "dragon", "ender");
    private static final Pattern CUSTOM_CODE = Pattern.compile("custom_([0-9a-f]{64})");

    private CapeProtocol() {}

    static CapeAssignment assignmentFor(String code) {
        if (code == null) return null;
        if (BUILTIN_IDS.contains(code)) return CapeAssignment.builtin(code);
        Matcher custom = CUSTOM_CODE.matcher(code);
        return custom.matches() ? CapeAssignment.custom(custom.group(1)) : null;
    }

    static Map<UUID, CapeAssignment> parseResponse(String json, Collection<UUID> requested) throws IOException {
        final JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (RuntimeException e) {
            throw new IOException("Cape API response is not valid JSON", e);
        }
        if (!root.isJsonObject()) throw new IOException("Cape API response is not an object");
        JsonObject response = root.getAsJsonObject();
        if (!response.has("success") || !response.get("success").isJsonPrimitive()
                || !response.getAsJsonPrimitive("success").isBoolean()
                || !response.get("success").getAsBoolean()) {
            throw new IOException("Cape API rejected request");
        }
        if (!response.has("version") || !response.get("version").isJsonPrimitive()
                || !response.getAsJsonPrimitive("version").isNumber()
                || !"2".equals(response.get("version").getAsString())) {
            throw new IOException("Cape API did not return protocol version 2");
        }
        if (!response.has("capes") || !response.get("capes").isJsonObject()) {
            throw new IOException("Cape API response has no capes object");
        }

        Set<UUID> requestedSet = Set.copyOf(requested);
        Map<UUID, CapeAssignment> result = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : response.getAsJsonObject("capes").entrySet()) {
            try {
                UUID uuid = UUID.fromString(entry.getKey());
                if (!requestedSet.contains(uuid) || !entry.getValue().isJsonPrimitive()) continue;
                CapeAssignment assignment = assignmentFor(entry.getValue().getAsString());
                if (assignment != null) result.put(uuid, assignment);
            } catch (IllegalArgumentException | UnsupportedOperationException ignored) {
                // Ignore malformed IDs, unexpected UUIDs, and values outside the public contract.
            }
        }
        return result;
    }
}

record CapeAssignment(String builtinId, String customHash) {
    static CapeAssignment builtin(String id) {
        return new CapeAssignment(id, null);
    }

    static CapeAssignment custom(String hash) {
        return new CapeAssignment(null, hash);
    }

    boolean isCustom() {
        return customHash != null;
    }

    String code() {
        return isCustom() ? "custom_" + customHash : builtinId;
    }
}
