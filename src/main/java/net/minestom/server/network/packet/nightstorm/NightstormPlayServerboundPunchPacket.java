package net.minestom.server.network.packet.nightstorm;

import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.network.NetworkBufferTemplate;
import net.minestom.server.network.packet.client.ClientPacket;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public record NightstormPlayServerboundPunchPacket() implements ClientPacket.Play {
    public static final NetworkBuffer.Type<NightstormPlayServerboundPunchPacket> SERIALIZER = NetworkBufferTemplate.template(new NightstormPlayServerboundPunchPacket());
}
