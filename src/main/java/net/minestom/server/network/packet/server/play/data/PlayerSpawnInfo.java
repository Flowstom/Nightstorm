package net.minestom.server.network.packet.server.play.data;

import net.minestom.server.entity.GameMode;
import net.minestom.server.network.NetworkBuffer;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

import static net.minestom.server.network.NetworkBuffer.BOOLEAN;
import static net.minestom.server.network.NetworkBuffer.STRING;
import static net.minestom.server.network.NetworkBuffer.VAR_INT;

public record PlayerSpawnInfo(int dimensionType, String world, GameMode gameMode,
                              @Nullable GameMode previousGameMode, boolean debug, boolean flat,
                              @Nullable WorldPos deathLocation, int portalCooldown, int seaLevel) {
    public static final NetworkBuffer.Type<PlayerSpawnInfo> NETWORK_TYPE = new NetworkBuffer.Type<PlayerSpawnInfo>() {

    @Override
    public void write(NetworkBuffer buffer, PlayerSpawnInfo value1) {
        buffer.write(VAR_INT, value1.dimensionType());
        buffer.write(STRING, value1.world());
        buffer.write(GameMode.NETWORK_TYPE, value1.gameMode());
        buffer.write(NetworkBuffer.OPTIONAL_VAR_INT.transform((Integer id) -> id == null ? null : switch(id) {
            case 0 ->
                GameMode.SURVIVAL;
            case 1 ->
                GameMode.CREATIVE;
            case 2 ->
                GameMode.ADVENTURE;
            case 3 ->
                GameMode.SPECTATOR;
            default ->
                throw new IllegalArgumentException("Unknown GameMode id: " + id);
        }, (GameMode value) -> value == null ? null : switch(value) {
            case SURVIVAL ->
                0;
            case CREATIVE ->
                1;
            case ADVENTURE ->
                2;
            case SPECTATOR ->
                3;
        }), value1.previousGameMode());
        buffer.write(BOOLEAN, value1.debug());
        buffer.write(BOOLEAN, value1.flat());
        buffer.write(WorldPos.NETWORK_TYPE.optional(), value1.deathLocation());
        buffer.write(VAR_INT, value1.portalCooldown());
        buffer.write(VAR_INT, value1.seaLevel());
    }

    @Override
    public PlayerSpawnInfo read(NetworkBuffer buffer) {
        int field0 = buffer.read(VAR_INT);
        String field1 = buffer.read(STRING);
        GameMode field2 = buffer.read(GameMode.NETWORK_TYPE);
        GameMode field3 = buffer.read(NetworkBuffer.OPTIONAL_VAR_INT.transform((Integer id) -> id == null ? null : switch(id) {
            case 0 ->
                GameMode.SURVIVAL;
            case 1 ->
                GameMode.CREATIVE;
            case 2 ->
                GameMode.ADVENTURE;
            case 3 ->
                GameMode.SPECTATOR;
            default ->
                throw new IllegalArgumentException("Unknown GameMode id: " + id);
        }, (GameMode value) -> value == null ? null : switch(value) {
            case SURVIVAL ->
                0;
            case CREATIVE ->
                1;
            case ADVENTURE ->
                2;
            case SPECTATOR ->
                3;
        }));
        boolean field4 = buffer.read(BOOLEAN);
        boolean field5 = buffer.read(BOOLEAN);
        WorldPos field6 = buffer.read(WorldPos.NETWORK_TYPE.optional());
        int field7 = buffer.read(VAR_INT);
        int field8 = buffer.read(VAR_INT);
        return new PlayerSpawnInfo(field0, field1, field2, field3, field4, field5, field6, field7, field8);
    }
};

    public PlayerSpawnInfo {
        Objects.requireNonNull(gameMode, "gameMode");
    }
    
    public PlayerSpawnInfo(int dimensionType, String world, long hashedSeed, GameMode gameMode, @Nullable GameMode previousGameMode, boolean debug, boolean flat, @Nullable WorldPos deathLocation, int portalCooldown, int seaLevel) { this(dimensionType, world, gameMode, previousGameMode, debug, flat, deathLocation, portalCooldown, seaLevel); }
}
