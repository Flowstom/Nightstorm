package net.minestom.server.network.packet.nightstorm;

import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.network.packet.server.ServerPacket;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public record NightstormPlayClientboundSwingAnimationPacket(byte[] payload) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<NightstormPlayClientboundSwingAnimationPacket> SERIALIZER =
            NetworkBuffer.RAW_BYTES.transform(NightstormPlayClientboundSwingAnimationPacket::new, NightstormPlayClientboundSwingAnimationPacket::payload);
}
