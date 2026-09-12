package net.minestom.server.network.packet.server.play;

import net.minestom.server.coordinate.Point;
import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.network.packet.server.ServerPacket;
import net.minestom.server.particle.Particle;

import java.util.Objects;

import static net.minestom.server.network.NetworkBuffer.BOOLEAN;
import static net.minestom.server.network.NetworkBuffer.DOUBLE;
import static net.minestom.server.network.NetworkBuffer.FLOAT;
import static net.minestom.server.network.NetworkBuffer.VAR_INT;

public record ParticlePacket(Particle particle, boolean overrideLimiter, boolean longDistance, double x, double y, double z,
                             float offsetX, float offsetY, float offsetZ, float maxSpeed,
                             int particleCount) implements ServerPacket.Play {
    public ParticlePacket(Particle particle, double x, double y, double z, float offsetX, float offsetY, float offsetZ, float maxSpeed, int particleCount) {
        this(particle, false, false, x, y, z, offsetX, offsetY, offsetZ, maxSpeed, particleCount);
    }

    public ParticlePacket(Particle particle, boolean overrideLimiter, boolean longDistance, Point position, Point offset, float maxSpeed, int particleCount) {
        this(particle, overrideLimiter, longDistance, position.x(), position.y(), position.z(), (float) offset.x(), (float) offset.y(), (float) offset.z(), maxSpeed, particleCount);
    }

    public ParticlePacket(Particle particle, Point position, Point offset, float maxSpeed, int particleCount) {
        this(particle, false, false, position, offset, maxSpeed, particleCount);
    }

    public static final NetworkBuffer.Type<ParticlePacket> SERIALIZER = new NetworkBuffer.Type<ParticlePacket>() {
    @Override
    public void write(NetworkBuffer buffer, ParticlePacket value) {
        buffer.write(VAR_INT, value.particle.id());
        value.particle.writeData(buffer);
        buffer.write(BOOLEAN, value.overrideLimiter);
        buffer.write(BOOLEAN, value.longDistance);
        buffer.write(DOUBLE, value.x);
        buffer.write(DOUBLE, value.y);
        buffer.write(DOUBLE, value.z);
        buffer.write(FLOAT, value.offsetX);
        buffer.write(FLOAT, value.offsetY);
        buffer.write(FLOAT, value.offsetZ);
        buffer.write(FLOAT, value.maxSpeed);
        buffer.write(FLOAT, value.maxSpeed);
        buffer.write(FLOAT, value.maxSpeed);
        buffer.write(VAR_INT, value.particleCount);
        buffer.write(VAR_INT, 0);
    }
    @Override
    public ParticlePacket read(NetworkBuffer buffer) {
        Particle particle = Objects.requireNonNull(Particle.fromId(buffer.read(VAR_INT))).readData(buffer);
        boolean overrideLimiter = buffer.read(BOOLEAN);
        boolean longDistance = buffer.read(BOOLEAN);
        double x = buffer.read(DOUBLE);
        double y = buffer.read(DOUBLE);
        double z = buffer.read(DOUBLE);
        float offsetX = buffer.read(FLOAT);
        float offsetY = buffer.read(FLOAT);
        float offsetZ = buffer.read(FLOAT);
        float maxSpeed = buffer.read(FLOAT);
        float ySpeed = buffer.read(FLOAT);
        float zSpeed = buffer.read(FLOAT);
        int count = buffer.read(VAR_INT);
        int randomization = buffer.read(VAR_INT);
        if (Float.compare(maxSpeed, ySpeed) != 0 || Float.compare(maxSpeed, zSpeed) != 0 || randomization != 0) {
            throw new IllegalArgumentException("Particle payload cannot be represented by the retained scalar-speed API");
        }
        return new ParticlePacket(particle, overrideLimiter, longDistance, x, y, z, offsetX, offsetY, offsetZ, maxSpeed, count);
    }
};
}
