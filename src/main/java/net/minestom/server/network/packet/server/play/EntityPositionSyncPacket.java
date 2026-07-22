package net.minestom.server.network.packet.server.play;

import net.minestom.server.coordinate.Point;
import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.network.NetworkBufferTemplate;
import net.minestom.server.network.packet.server.ServerPacket;

import static net.minestom.server.network.NetworkBuffer.*;

public record EntityPositionSyncPacket(
        int entityId, Point position, Point delta,
        float yaw, float pitch, boolean onGround
) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<EntityPositionSyncPacket> SERIALIZER = new NetworkBuffer.Type<EntityPositionSyncPacket>() {
    @Override
    public void write(NetworkBuffer buffer, EntityPositionSyncPacket value) {
        buffer.write(VAR_INT, value.entityId());
        buffer.write(NetworkBuffer.VAR_INT, 0);
        buffer.write(VECTOR3D, value.position());
        buffer.write(FLOAT, value.yaw());
        buffer.write(FLOAT, value.pitch());
        buffer.write(BOOLEAN, value.onGround());
    }

    @Override
    public EntityPositionSyncPacket read(NetworkBuffer buffer) {
        int component0 = buffer.read(VAR_INT);
         int discriminator = buffer.read(NetworkBuffer.VAR_INT);
        if (discriminator != 0) throw new IllegalArgumentException("Unsupported position path id: " + discriminator);
        Point component1 = buffer.read(VECTOR3D);
        Point component2 = component1;
        float component3 = buffer.read(FLOAT);
        float component4 = buffer.read(FLOAT);
        boolean component5 = buffer.read(BOOLEAN);
        return new EntityPositionSyncPacket(component0, component1, component2, component3, component4, component5);
    }
};
}
