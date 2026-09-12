package net.minestom.server.network.packet.client.play;

import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.network.NetworkBufferTemplate;
import net.minestom.server.network.packet.client.ClientPacket;

import static net.minestom.server.network.NetworkBuffer.VAR_INT;

public record ClientTeleportConfirmPacket(int teleportId) implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientTeleportConfirmPacket> SERIALIZER = new NetworkBuffer.Type<ClientTeleportConfirmPacket>() {
    private final NetworkBuffer.Type<ClientTeleportConfirmPacket> teleportPositionDelegate = NetworkBufferTemplate.template(
            VAR_INT, ClientTeleportConfirmPacket::teleportId,
            ClientTeleportConfirmPacket::new);
    @Override
    public void write(NetworkBuffer buffer, ClientTeleportConfirmPacket value) {
        teleportPositionDelegate.write(buffer, value);
        buffer.write(NetworkBuffer.DOUBLE, 0d);
        buffer.write(NetworkBuffer.DOUBLE, 0d);
        buffer.write(NetworkBuffer.DOUBLE, 0d);
        buffer.write(NetworkBuffer.FLOAT, 0f);
        buffer.write(NetworkBuffer.FLOAT, 0f);
    }
    @Override
    public ClientTeleportConfirmPacket read(NetworkBuffer buffer) {
        var value = teleportPositionDelegate.read(buffer);
        buffer.read(NetworkBuffer.DOUBLE);
        buffer.read(NetworkBuffer.DOUBLE);
        buffer.read(NetworkBuffer.DOUBLE);
        buffer.read(NetworkBuffer.FLOAT);
        buffer.read(NetworkBuffer.FLOAT);
        return value;
    }
};
}
