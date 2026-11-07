package dev.bytefg.blmcapes;

import java.util.List;
import java.util.UUID;
import net.minecraftforge.common.ForgeConfigSpec;

public final class CapeConfig {
    public static final ForgeConfigSpec SPEC;
    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> TEST_PLAYERS;
    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.comment("Локальная отладка: UUID игроков, которым этот клиент принудительно показывает плащ BLM.",
                "Этот список имеет приоритет над публичной синхронизацией и не отправляется в сеть.");
        TEST_PLAYERS = builder.defineListAllowEmpty("testPlayerUuids", List.<String>of(),
                value -> value instanceof String);
        SPEC = builder.build();
    }
    private CapeConfig() {}

    /**
     * Existing local test list remains an explicit debug override and takes precedence over API data.
     */
    public static String getDebugCape(UUID uuid) {
        if (uuid == null) return null;
        for (String value : TEST_PLAYERS.get()) {
            try {
                if (uuid.equals(UUID.fromString(value.trim()))) return "blm";
            } catch (IllegalArgumentException ignored) {
                // Ошибочный UUID не должен ломать запуск или рендер игры.
            }
        }
        return null;
    }

    @Deprecated(forRemoval = false)
    public static boolean showsCape(UUID uuid) {
        return getDebugCape(uuid) != null;
    }
}
