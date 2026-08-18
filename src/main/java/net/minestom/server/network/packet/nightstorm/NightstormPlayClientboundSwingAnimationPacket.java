package net.minestom.server.network.packet.nightstorm;

import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.network.NetworkBufferTemplate;
import net.minestom.server.network.packet.server.ServerPacket;
import net.minestom.server.entity.PlayerHand;
import net.minestom.server.item.component.SwingAnimation;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public record NightstormPlayClientboundSwingAnimationPacket(int entityId, PlayerHand hand, SwingAnimation animation) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<NightstormPlayClientboundSwingAnimationPacket> SERIALIZER = NetworkBufferTemplate.template(
            NetworkBuffer.VAR_INT, NightstormPlayClientboundSwingAnimationPacket::entityId,
            NetworkBuffer.Enum(PlayerHand.class), NightstormPlayClientboundSwingAnimationPacket::hand,
            SwingAnimation.NETWORK_TYPE, NightstormPlayClientboundSwingAnimationPacket::animation,
            NightstormPlayClientboundSwingAnimationPacket::new
    );
}
