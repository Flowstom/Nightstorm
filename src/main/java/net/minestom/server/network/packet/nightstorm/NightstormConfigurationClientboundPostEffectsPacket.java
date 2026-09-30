package net.minestom.server.network.packet.nightstorm;

import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.network.packet.server.ServerPacket;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public record NightstormConfigurationClientboundPostEffectsPacket(byte[] payload) implements ServerPacket.Configuration {
    public static final NetworkBuffer.Type<NightstormConfigurationClientboundPostEffectsPacket> SERIALIZER =
            NetworkBuffer.RAW_BYTES.transform(NightstormConfigurationClientboundPostEffectsPacket::new, NightstormConfigurationClientboundPostEffectsPacket::payload);
}
