package net.minestom.server.network.packet.nightstorm;

import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.network.packet.server.ServerPacket;
import java.util.List;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public record NightstormConfigurationClientboundPostEffectsPacket(List<Key> postEffects) implements ServerPacket.Configuration {
    public static final NetworkBuffer.Type<NightstormConfigurationClientboundPostEffectsPacket> SERIALIZER = NetworkBuffer.KEY.list().transform(NightstormConfigurationClientboundPostEffectsPacket::new, NightstormConfigurationClientboundPostEffectsPacket::postEffects);
}
