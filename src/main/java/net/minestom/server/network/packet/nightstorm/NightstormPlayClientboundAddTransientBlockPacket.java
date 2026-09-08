package net.minestom.server.network.packet.nightstorm;

import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.network.NetworkBufferTemplate;
import net.minestom.server.network.packet.server.ServerPacket;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public record NightstormPlayClientboundAddTransientBlockPacket() implements ServerPacket.Play {
    public static final NetworkBuffer.Type<NightstormPlayClientboundAddTransientBlockPacket> SERIALIZER = NetworkBufferTemplate.template(new NightstormPlayClientboundAddTransientBlockPacket());
}
