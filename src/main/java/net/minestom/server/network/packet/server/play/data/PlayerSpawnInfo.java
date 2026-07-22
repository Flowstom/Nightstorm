package net.minestom.server.network.packet.server.play.data;

import net.minestom.server.entity.GameMode;
import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.network.NetworkBufferTemplate;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

import static net.minestom.server.network.NetworkBuffer.*;

public record PlayerSpawnInfo(int dimensionType, String world, long hashedSeed, GameMode gameMode,
                              @Nullable GameMode previousGameMode, boolean debug, boolean flat,
                              @Nullable WorldPos deathLocation, int portalCooldown, int seaLevel) {
    public static final NetworkBuffer.Type<PlayerSpawnInfo> NETWORK_TYPE = NetworkBufferTemplate.template(
            VAR_INT, PlayerSpawnInfo::dimensionType,
            STRING, PlayerSpawnInfo::world,
            LONG, PlayerSpawnInfo::hashedSeed,
            GameMode.NETWORK_TYPE, PlayerSpawnInfo::gameMode,
            NetworkBuffer.OPTIONAL_VAR_INT.transform(
        (Integer id) -> id == null ? null : switch (id) {
            case 0 -> GameMode.SURVIVAL;
            case 1 -> GameMode.CREATIVE;
            case 2 -> GameMode.ADVENTURE;
            case 3 -> GameMode.SPECTATOR;
            default -> throw new IllegalArgumentException("Unknown GameMode id: " + id);
        },
        (GameMode value) -> value == null ? null : switch (value) {
            case ADVENTURE -> 2;
            case SPECTATOR -> 3;
            case SURVIVAL -> 0;
            case CREATIVE -> 1;
        }
), PlayerSpawnInfo::previousGameMode,
            BOOLEAN, PlayerSpawnInfo::debug,
            BOOLEAN, PlayerSpawnInfo::flat,
            WorldPos.NETWORK_TYPE.optional(), PlayerSpawnInfo::deathLocation,
            VAR_INT, PlayerSpawnInfo::portalCooldown,
            VAR_INT, PlayerSpawnInfo::seaLevel,
            PlayerSpawnInfo::new
    );

    public PlayerSpawnInfo {
        Objects.requireNonNull(gameMode, "gameMode");
    }
}
