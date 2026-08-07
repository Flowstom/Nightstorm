package net.minestom.server.network.packet.client.play;

import net.minestom.server.coordinate.Point;
import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.network.packet.client.ClientPacket;

import java.util.List;

import static net.minestom.server.network.NetworkBuffer.*;

public record ClientUpdateSignPacket(
        Point blockPosition,
        boolean isFrontText,
        List<String> lines
) implements ClientPacket.Play {
    public ClientUpdateSignPacket {
        lines = List.copyOf(lines);
        if (lines.size() != 4) {
            throw new IllegalArgumentException("Signs must have 4 lines!");
        }
        for (String line : lines) {
            if (line.length() > 384) {
                throw new IllegalArgumentException("Signs must have a maximum of 384 characters per line!");
            }
        }
    }

    public static final NetworkBuffer.Type<ClientUpdateSignPacket> SERIALIZER = new NetworkBuffer.Type<ClientUpdateSignPacket>() {
    @Override
    public void write(NetworkBuffer buffer, ClientUpdateSignPacket value) {
        buffer.write(BLOCK_POSITION, value.blockPosition());
            buffer.write(STRING, value.lines().get(0));
            buffer.write(STRING, value.lines().get(1));
            buffer.write(STRING, value.lines().get(2));
            buffer.write(STRING, value.lines().get(3));
        buffer.write(NetworkBuffer.VAR_INT, value.isFrontText() ? 1 : 0);
    }

    @Override
    public ClientUpdateSignPacket read(NetworkBuffer buffer) {
        var objectValue = buffer.read(BLOCK_POSITION);
        var stringValues = List.of(buffer.read(STRING), buffer.read(STRING), buffer.read(STRING), buffer.read(STRING));
        int booleanValueId = buffer.read(NetworkBuffer.VAR_INT);
        boolean booleanValue;
        if (booleanValueId == 1) booleanValue = true;
        else if (booleanValueId == 0) booleanValue = false;
        else throw new IllegalArgumentException("Unknown binary enum id: " + booleanValueId);
        return new ClientUpdateSignPacket(objectValue, booleanValue, stringValues);
    }
};
}
