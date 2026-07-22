package net.flowstom.nightstorm;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PacketUpdaterTest {
    @Test
    void reordersRemovesAndGeneratesPacketRegistrations() throws Exception {
        final var sourceRoot = Files.createTempDirectory("nightstorm-source");
        final var packetDirectory = sourceRoot.resolve("src/main/java/net/minestom/server/network/packet");
        Files.createDirectories(packetDirectory);
        final var packetVanilla = packetDirectory.resolve("PacketVanilla.java");
        Files.writeString(packetVanilla, """
                package net.minestom.server.network.packet;

                import net.minestom.server.network.packet.server.ServerPacket;

                final class PacketVanilla {
                    static final PacketRegistry<ServerPacket.Play> SERVER_PLAY = registry(ConnectionState.PLAY, PacketRegistry.ConnectionSide.SERVER,
                            entry(AlphaPacket.class, AlphaPacket.SERIALIZER),
                            entry(BetaPacket.class, BetaPacket.SERIALIZER)
                    );
                }
                """);
        final var baseline = report(
                packet(0, "CLIENTBOUND_ALPHA"),
                packet(1, "CLIENTBOUND_BETA"));
        final var target = report(
                packet(0, "CLIENTBOUND_BETA"),
                packet(1, "CLIENTBOUND_NEW_EFFECT"));

        final var result = PacketUpdater.update(sourceRoot, baseline, target, ignored ->
                new PacketCodecScanner.PacketShape(List.of(
                        new PacketCodecScanner.PacketField("effects", "java.util.List<net.kyori.adventure.key.Key>",
                                "NetworkBuffer.KEY.list()"))));

        assertEquals(2, result.packets());
        assertEquals(1, result.generatedPackets());
        assertTrue(result.warnings().isEmpty());
        final String updated = Files.readString(packetVanilla);
        assertFalse(updated.contains("AlphaPacket.class"));
        assertTrue(updated.indexOf("BetaPacket.class") < updated.indexOf("NightstormPlayClientboundNewEffectPacket.class"));
        assertTrue(updated.contains("import net.minestom.server.network.packet.nightstorm.*;"));
        final String generated = Files.readString(packetDirectory.resolve("nightstorm/NightstormPlayClientboundNewEffectPacket.java"));
        assertTrue(generated.contains("implements ServerPacket.Play"));
        assertTrue(generated.contains("record NightstormPlayClientboundNewEffectPacket(List<Key> effects)"));
        assertTrue(generated.contains("NetworkBuffer.KEY.list().transform"));
    }

    @Test
    void generatesOpaquePacketAndWarningForUnsupportedCodec() throws Exception {
        final var sourceRoot = Files.createTempDirectory("nightstorm-source");
        final var packetDirectory = sourceRoot.resolve("src/main/java/net/minestom/server/network/packet");
        Files.createDirectories(packetDirectory);
        Files.writeString(packetDirectory.resolve("PacketVanilla.java"), """
                package net.minestom.server.network.packet;
                import net.minestom.server.network.packet.server.ServerPacket;
                final class PacketVanilla {
                    static PacketRegistry<ServerPacket.Play> SERVER_PLAY = registry(ConnectionState.PLAY, PacketRegistry.ConnectionSide.SERVER);
                }
                """);
        final var target = report(packet(0, "CLIENTBOUND_UNKNOWN"));

        final var result = PacketUpdater.update(sourceRoot, report(), target,
                ignored -> PacketCodecScanner.PacketShape.opaque("unsupported test codec"));

        assertEquals(1, result.warnings().size());
        final String generated = Files.readString(packetDirectory.resolve("nightstorm/NightstormPlayClientboundUnknownPacket.java"));
        assertTrue(generated.contains("NetworkBuffer.RAW_BYTES"));
        final String warnings = Files.readString(sourceRoot.resolve(".nightstorm/packet-warnings.md"));
        assertTrue(warnings.contains("CLIENTBOUND_UNKNOWN"));
        assertTrue(warnings.contains("unsupported test codec"));
    }

    @Test
    void removesGeneratedPacketImportWhenNoPacketsAreGenerated() throws Exception {
        final var sourceRoot = Files.createTempDirectory("nightstorm-source");
        final var packetDirectory = sourceRoot.resolve("src/main/java/net/minestom/server/network/packet");
        final var generatedDirectory = packetDirectory.resolve("nightstorm");
        Files.createDirectories(generatedDirectory);
        final var packetVanilla = packetDirectory.resolve("PacketVanilla.java");
        Files.writeString(packetVanilla, """
                package net.minestom.server.network.packet;
                import net.minestom.server.network.packet.nightstorm.*;
                import net.minestom.server.network.packet.server.ServerPacket;
                final class PacketVanilla {
                    static PacketRegistry<ServerPacket.Play> SERVER_PLAY = registry(ConnectionState.PLAY, PacketRegistry.ConnectionSide.SERVER,
                            entry(AlphaPacket.class, AlphaPacket.SERIALIZER)
                    );
                }
                """);
        final var stalePacket = generatedDirectory.resolve("NightstormPlayClientboundStalePacket.java");
        Files.writeString(stalePacket, "stale");
        final var packets = report(packet(0, "CLIENTBOUND_ALPHA"));

        final var result = PacketUpdater.update(sourceRoot, packets, packets, ignored -> null);

        assertEquals(0, result.generatedPackets());
        assertFalse(Files.readString(packetVanilla).contains("packet.nightstorm.*"));
        assertFalse(Files.exists(stalePacket));
    }

    @Test
    void regeneratesReferencedPacketsWhenBaselineMatchesTarget() throws Exception {
        final var sourceRoot = Files.createTempDirectory("nightstorm-source");
        final var packetDirectory = sourceRoot.resolve("src/main/java/net/minestom/server/network/packet");
        final var generatedDirectory = packetDirectory.resolve("nightstorm");
        Files.createDirectories(generatedDirectory);
        final var packetVanilla = packetDirectory.resolve("PacketVanilla.java");
        Files.writeString(packetVanilla, """
                package net.minestom.server.network.packet;
                import net.minestom.server.network.packet.nightstorm.*;
                import net.minestom.server.network.packet.server.ServerPacket;
                final class PacketVanilla {
                    static PacketRegistry<ServerPacket.Play> SERVER_PLAY = registry(ConnectionState.PLAY, PacketRegistry.ConnectionSide.SERVER,
                            entry(NightstormPlayClientboundNewEffectPacket.class, NightstormPlayClientboundNewEffectPacket.SERIALIZER)
                    );
                }
                """);
        final var generatedPacket = generatedDirectory.resolve("NightstormPlayClientboundNewEffectPacket.java");
        Files.writeString(generatedPacket, "stale");
        final var packets = report(packet(0, "CLIENTBOUND_NEW_EFFECT"));

        final var result = PacketUpdater.update(sourceRoot, packets, packets, ignored ->
                new PacketCodecScanner.PacketShape(List.of(new PacketCodecScanner.PacketField(
                        "effects", "java.util.List<net.kyori.adventure.key.Key>", "NetworkBuffer.KEY.list()"))));

        assertEquals(1, result.generatedPackets());
        assertTrue(Files.readString(packetVanilla).contains("packet.nightstorm.*"));
        final String generatedSource = Files.readString(generatedPacket);
        assertTrue(generatedSource.contains("import java.util.List;"));
        assertTrue(generatedSource.contains("import net.kyori.adventure.key.Key;"));
        assertTrue(generatedSource.contains("record NightstormPlayClientboundNewEffectPacket(List<Key> effects)"));
        assertTrue(generatedSource.contains("NetworkBuffer.KEY.list().transform"));
    }

    private static PacketScanner.PacketReport report(PacketScanner.PacketCandidate... packets) {
        return new PacketScanner.PacketReport(Instant.EPOCH.toString(), List.of(packets));
    }

    private static PacketScanner.PacketCandidate packet(int id, String name) {
        return new PacketScanner.PacketCandidate("play", "clientbound", id,
                "net.minecraft.network.protocol.game.GamePacketTypes#" + name,
                "net.minecraft.network.protocol.game." + name, "existing-or-opaque");
    }
}
