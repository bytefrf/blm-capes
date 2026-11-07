package dev.bytefg.blmcapes;

import java.util.List;
import java.util.UUID;
import net.minecraftforge.common.ForgeConfigSpec;

public final class CapeConfig {
    public static final ForgeConfigSpec SPEC;
    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> TEST_PLAYERS;
    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.comment("Локальный тест: UUID игроков, которым этот клиент показывает плащ.",
                "Пустой список отключает плащи. Покупки и синхронизация не подключены.");
        TEST_PLAYERS = builder.defineListAllowEmpty("testPlayerUuids", List.<String>of(),
                value -> value instanceof String);
        SPEC = builder.build();
    }
    private CapeConfig() {}

    public static boolean showsCape(UUID uuid) {
        if (uuid == null) return false;
        for (String value : TEST_PLAYERS.get()) {
            try {
                if (uuid.equals(UUID.fromString(value.trim()))) return true;
            } catch (IllegalArgumentException ignored) {
                // Ошибочный UUID не должен ломать запуск или рендер игры.
            }
        }
        return false;
    }
}
