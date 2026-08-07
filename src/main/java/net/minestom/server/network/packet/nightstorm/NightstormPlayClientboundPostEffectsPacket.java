package net.minestom.server.network.packet.nightstorm;

import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.network.packet.server.ServerPacket;
import java.util.List;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public record NightstormPlayClientboundPostEffectsPacket(List<Key> postEffects) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<NightstormPlayClientboundPostEffectsPacket> SERIALIZER = NetworkBuffer.KEY.list().transform(NightstormPlayClientboundPostEffectsPacket::new, NightstormPlayClientboundPostEffectsPacket::postEffects);
}
