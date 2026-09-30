package net.minestom.server.network.packet.server.common;

import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.network.packet.server.ServerPacket;
import java.util.Map;

public record TransferPacket(
        String host,
        int port, Map<String, String> properties
) implements ServerPacket.Configuration, ServerPacket.Play {
    public static final NetworkBuffer.Type<TransferPacket> SERIALIZER = new NetworkBuffer.Type<TransferPacket>() {

    @Override
    public void write(NetworkBuffer buffer, TransferPacket value) {
        buffer.write(NetworkBuffer.STRING, value.host());
        buffer.write(NetworkBuffer.VAR_INT, value.port());
        buffer.write(NetworkBuffer.STRING.mapValue(NetworkBuffer.STRING), value.properties());
    }

    @Override
    public TransferPacket read(NetworkBuffer buffer) {
        String field0 = buffer.read(NetworkBuffer.STRING);
        int field1 = buffer.read(NetworkBuffer.VAR_INT);
        Map<String, String> field2 = buffer.read(NetworkBuffer.STRING.mapValue(NetworkBuffer.STRING));
        return new TransferPacket(field0, field1, field2);
    }
};
    
    public TransferPacket(String host, int port) { this(host, port, Map.of()); }
}
