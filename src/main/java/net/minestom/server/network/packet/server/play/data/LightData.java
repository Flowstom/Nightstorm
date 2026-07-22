package net.minestom.server.network.packet.server.play.data;

import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.network.NetworkBufferTemplate;

import java.util.BitSet;
import java.util.List;

import static net.minestom.server.network.NetworkBuffer.BITSET;
import static net.minestom.server.network.NetworkBuffer.BYTE_ARRAY;

public record LightData(
        BitSet skyMask, BitSet blockMask,
        BitSet emptySkyMask, BitSet emptyBlockMask,
        List<byte[]> skyLight,
        List<byte[]> blockLight
) {
    public LightData {
        skyMask = (BitSet) skyMask.clone();
        blockMask = (BitSet) blockMask.clone();
        emptySkyMask = (BitSet) emptySkyMask.clone();
        emptyBlockMask = (BitSet) emptyBlockMask.clone();
        skyLight = List.copyOf(skyLight); //TODO deep copy?
        blockLight = List.copyOf(blockLight); //TODO deep copy?
    }

    public static final int MAX_SECTIONS = 4096 / 16;

    public static final NetworkBuffer.Type<LightData> NETWORK_TYPE = NetworkBufferTemplate.template(
            BYTE_ARRAY.transform(BitSet::valueOf, BitSet::toByteArray), LightData::skyMask,
            BYTE_ARRAY.transform(BitSet::valueOf, BitSet::toByteArray), LightData::blockMask,
            BYTE_ARRAY.transform(BitSet::valueOf, BitSet::toByteArray), LightData::emptySkyMask,
            BYTE_ARRAY.transform(BitSet::valueOf, BitSet::toByteArray), LightData::emptyBlockMask,
            BYTE_ARRAY.list(MAX_SECTIONS), LightData::skyLight,
            BYTE_ARRAY.list(MAX_SECTIONS), LightData::blockLight,
            LightData::new
    );
}
