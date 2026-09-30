package net.flowstom.nightstorm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in oracle test against locally prepared vanilla and generated Minestom runtimes. */
@EnabledIfEnvironmentVariable(named = "NIGHTSTORM_CODEC_CLASSPATHS", matches = ".+")
class VanillaCodecIntegrationTest {
    @Test
    void generatedSerializersAgreeWithVanillaAndConsumeWholePackets() throws Exception {
        final var stdout = System.out;
        final var stderr = System.err;
        try (var vanilla = loader("vanilla"); var generated = loader("generated")) {
            vanilla.loadClass("net.minecraft.SharedConstants").getMethod("tryDetectVersion").invoke(null);
            vanilla.loadClass("net.minecraft.server.Bootstrap").getMethod("bootStrap").invoke(null);
            final var bufferClass = generated.loadClass("net.minestom.server.network.NetworkBuffer");
            final var bufferType = generated.loadClass("net.minestom.server.network.NetworkBuffer$Type");
            final var streamCodec = vanilla.loadClass("net.minecraft.network.codec.StreamCodec");
            final var byteBuf = vanilla.loadClass("io.netty.buffer.ByteBuf");
            final var unpooled = vanilla.loadClass("io.netty.buffer.Unpooled");
            final var registryAccess = vanilla.loadClass("net.minecraft.core.RegistryAccess");
            final Object registries = registryAccess.getMethod("fromRegistryOfRegistries", vanilla.loadClass("net.minecraft.core.Registry"))
                    .invoke(null, vanilla.loadClass("net.minecraft.core.registries.BuiltInRegistries").getField("REGISTRY").get(null));
            final var registryBuffer = vanilla.loadClass("net.minecraft.network.RegistryFriendlyByteBuf")
                    .getConstructor(byteBuf, registryAccess);

            final var particleClass = generated.loadClass("net.minestom.server.particle.Particle");
            final var packetClass = generated.loadClass("net.minestom.server.network.packet.server.play.ParticlePacket");
            final var flame = particleClass.getField("FLAME");
            flame.setAccessible(true);
            final Object packet = packetClass.getConstructor(particleClass, boolean.class, boolean.class, double.class, double.class,
                            double.class, float.class, float.class, float.class, float.class, int.class)
                    .newInstance(flame.get(null), true, false, 1.25d, -2.5d, 300d, 0.1f, 0.2f, 0.3f, 0.75f, 500);
            final Object serializer = packetClass.getField("SERIALIZER").get(null);
            final byte[] bytes = (byte[]) bufferClass.getMethod("makeArray", bufferType, Object.class).invoke(null, serializer, packet);
            final Object input = registryBuffer.newInstance(unpooled.getMethod("wrappedBuffer", byte[].class).invoke(null, (Object) bytes), registries);
            final var vanillaPacket = vanilla.loadClass("net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket");
            final Object codec = vanillaPacket.getField("STREAM_CODEC").get(null);
            final Object decoded = streamCodec.getMethod("decode", Object.class).invoke(codec, input);
            assertEquals(0, byteBuf.getMethod("readableBytes").invoke(input));
            assertEquals(500, vanillaPacket.getMethod("count").invoke(decoded));
            assertEquals(1.25d, vanillaPacket.getMethod("x").invoke(decoded));
            for (String axis : new String[]{"xMaxSpeed", "yMaxSpeed", "zMaxSpeed"}) {
                assertEquals(0.75f, vanillaPacket.getMethod(axis).invoke(decoded));
            }
            assertEquals("DEFAULT", ((Enum<?>) vanillaPacket.getMethod("randomizationType").invoke(decoded)).name());
            final Object output = registryBuffer.newInstance(unpooled.getMethod("buffer").invoke(null), registries);
            streamCodec.getMethod("encode", Object.class, Object.class).invoke(codec, output, decoded);
            final byte[] encoded = bytes(byteBuf, output);
            assertArrayEquals(bytes, encoded);
            final Object retainedInput = bufferClass.getMethod("wrap", byte[].class, int.class, int.class)
                    .invoke(null, encoded, 0, encoded.length);
            assertEquals(packet, bufferType.getMethod("read", bufferClass).invoke(serializer, retainedInput));
            assertEquals((long) encoded.length, bufferClass.getMethod("readIndex").invoke(retainedInput));
            byteBuf.getMethod("release").invoke(input);
            byteBuf.getMethod("release").invoke(output);

            final var teleport = vanilla.loadClass("net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket");
            final Object confirmation = teleport.getConstructor(int.class, double.class, double.class, double.class, float.class, float.class)
                    .newInstance(123456, 1d, -2d, 300d, 90f, -45f);
            final Object teleportOutput = vanilla.loadClass("net.minecraft.network.FriendlyByteBuf").getConstructor(byteBuf)
                    .newInstance(unpooled.getMethod("buffer").invoke(null));
            streamCodec.getMethod("encode", Object.class, Object.class).invoke(teleport.getField("STREAM_CODEC").get(null), teleportOutput, confirmation);
            final byte[] confirmationBytes = bytes(byteBuf, teleportOutput);
            final Object confirmationInput = bufferClass.getMethod("wrap", byte[].class, int.class, int.class)
                    .invoke(null, confirmationBytes, 0, confirmationBytes.length);
            final var retainedTeleport = generated.loadClass("net.minestom.server.network.packet.client.play.ClientTeleportConfirmPacket");
            final Object confirmationValue = bufferType.getMethod("read", bufferClass)
                    .invoke(retainedTeleport.getField("SERIALIZER").get(null), confirmationInput);
            assertEquals(123456, retainedTeleport.getMethod("teleportId").invoke(confirmationValue));
            assertEquals((long) confirmationBytes.length, bufferClass.getMethod("readIndex").invoke(confirmationInput));
            byteBuf.getMethod("release").invoke(teleportOutput);
            verifyTransferAndSpawn(vanilla, generated, registries);
        } finally {
            System.setOut(stdout);
            System.setErr(stderr);
        }
    }

    private static void verifyTransferAndSpawn(ClassLoader vanilla, ClassLoader generated, Object builtinRegistries) throws Exception {
        final var buffer = generated.loadClass("net.minestom.server.network.NetworkBuffer");
        final var bufferType = generated.loadClass("net.minestom.server.network.NetworkBuffer$Type");
        final var byteBuf = vanilla.loadClass("io.netty.buffer.ByteBuf");
        final var unpooled = vanilla.loadClass("io.netty.buffer.Unpooled");
        final var streamCodec = vanilla.loadClass("net.minecraft.network.codec.StreamCodec");
        final var transfer = generated.loadClass("net.minestom.server.network.packet.server.common.TransferPacket");
        final var properties = java.util.Map.of("destination", "example.org");
        final Object packet = transfer.getConstructor(String.class, int.class, java.util.Map.class)
                .newInstance("example.org", 25565, properties);
        final Object serializer = transfer.getField("SERIALIZER").get(null);
        final byte[] data = (byte[]) buffer.getMethod("makeArray", bufferType, Object.class).invoke(null, serializer, packet);
        final Object input = vanilla.loadClass("net.minecraft.network.FriendlyByteBuf").getConstructor(byteBuf)
                .newInstance(unpooled.getMethod("wrappedBuffer", byte[].class).invoke(null, (Object) data));
        final var vanillaTransfer = vanilla.loadClass("net.minecraft.network.protocol.common.ClientboundTransferPacket");
        final Object decoded = streamCodec.getMethod("decode", Object.class).invoke(vanillaTransfer.getField("STREAM_CODEC").get(null), input);
        assertEquals(properties, vanillaTransfer.getMethod("properties").invoke(decoded));
        assertEquals(0, byteBuf.getMethod("readableBytes").invoke(input));
        final Object output = vanilla.loadClass("net.minecraft.network.FriendlyByteBuf").getConstructor(byteBuf)
                .newInstance(unpooled.getMethod("buffer").invoke(null));
        streamCodec.getMethod("encode", Object.class, Object.class).invoke(vanillaTransfer.getField("STREAM_CODEC").get(null), output, decoded);
        assertArrayEquals(data, bytes(byteBuf, output));
        final Object sourceInput = buffer.getMethod("wrap", byte[].class, int.class, int.class).invoke(null, data, 0, data.length);
        assertEquals(packet, bufferType.getMethod("read", buffer).invoke(serializer, sourceInput));
        assertEquals((long) data.length, buffer.getMethod("readIndex").invoke(sourceInput));
        byteBuf.getMethod("release").invoke(input);
        byteBuf.getMethod("release").invoke(output);

        // Supply one genuine vanilla dimension at ID zero for the registry-backed spawn codec.
        final var keyType = vanilla.loadClass("net.minecraft.resources.ResourceKey");
        final var providerType = vanilla.loadClass("net.minecraft.core.HolderLookup$Provider");
        final var holderType = vanilla.loadClass("net.minecraft.core.Holder");
        final Object dimensionKey = vanilla.loadClass("net.minecraft.core.registries.Registries").getField("DIMENSION_TYPE").get(null);
        final Object provider = vanilla.loadClass("net.minecraft.data.registries.VanillaRegistries").getMethod("createWorldLookup").invoke(null);
        final Object lookup = providerType.getMethod("lookupOrThrow", keyType).invoke(provider, dimensionKey);
        final Object holder;
        try (var elements = (java.util.stream.Stream<?>) vanilla.loadClass("net.minecraft.core.HolderLookup")
                .getMethod("listElements").invoke(lookup)) {
            holder = elements.findFirst().orElseThrow();
        }
        final var lifecycle = vanilla.loadClass("com.mojang.serialization.Lifecycle");
        final var registry = vanilla.loadClass("net.minecraft.core.MappedRegistry");
        final Object dimensions = registry.getConstructor(keyType, lifecycle).newInstance(dimensionKey,
                lifecycle.getMethod("stable").invoke(null));
        final var registration = vanilla.loadClass("net.minecraft.core.RegistrationInfo");
        registry.getMethod("register", keyType, Object.class, registration).invoke(dimensions,
                ((java.util.Optional<?>) holderType.getMethod("unwrapKey").invoke(holder)).orElseThrow(),
                holderType.getMethod("value").invoke(holder), registration.getField("BUILT_IN").get(null));
        registry.getMethod("freeze").invoke(dimensions);
        final var access = vanilla.loadClass("net.minecraft.core.RegistryAccess");
        final Object registries = java.lang.reflect.Proxy.newProxyInstance(vanilla, new Class<?>[]{access}, (proxy, method, args) -> {
            if (method.getName().equals("lookup") && dimensionKey.equals(args[0])) return java.util.Optional.of(dimensions);
            if (method.isDefault()) return java.lang.reflect.InvocationHandler.invokeDefault(proxy, method, args);
            return method.invoke(builtinRegistries, args);
        });
        final var spawn = generated.loadClass("net.minestom.server.network.packet.server.play.data.PlayerSpawnInfo");
        final var mode = generated.loadClass("net.minestom.server.entity.GameMode");
        final var worldPos = generated.loadClass("net.minestom.server.network.packet.server.play.data.WorldPos");
        final Object value = spawn.getConstructor(int.class, String.class, long.class, mode, mode, boolean.class,
                        boolean.class, worldPos, int.class, int.class)
                .newInstance(0, "minecraft:overworld", 987654321L, mode.getField("SURVIVAL").get(null), null, false, false, null, 7, 64);
        final Object spawnCodec = spawn.getField("NETWORK_TYPE").get(null);
        final byte[] spawnBytes = (byte[]) buffer.getMethod("makeArray", bufferType, Object.class).invoke(null, spawnCodec, value);
        final var registryBuffer = vanilla.loadClass("net.minecraft.network.RegistryFriendlyByteBuf").getConstructor(byteBuf, access);
        final Object spawnInput = registryBuffer.newInstance(unpooled.getMethod("wrappedBuffer", byte[].class)
                .invoke(null, (Object) spawnBytes), registries);
        final var vanillaSpawn = vanilla.loadClass("net.minecraft.network.protocol.game.CommonPlayerSpawnInfo");
        final Object result = streamCodec.getMethod("decode", Object.class).invoke(vanillaSpawn.getField("STREAM_CODEC").get(null), spawnInput);
        assertEquals(7, vanillaSpawn.getMethod("portalCooldown").invoke(result));
        assertEquals(64, vanillaSpawn.getMethod("seaLevel").invoke(result));
        assertEquals(0, byteBuf.getMethod("readableBytes").invoke(spawnInput));
        final Object spawnOutput = registryBuffer.newInstance(unpooled.getMethod("buffer").invoke(null), registries);
        streamCodec.getMethod("encode", Object.class, Object.class).invoke(vanillaSpawn.getField("STREAM_CODEC").get(null), spawnOutput, result);
        assertArrayEquals(spawnBytes, bytes(byteBuf, spawnOutput));
        final Object retainedInput = buffer.getMethod("wrap", byte[].class, int.class, int.class)
                .invoke(null, spawnBytes, 0, spawnBytes.length);
        assertEquals(value, bufferType.getMethod("read", buffer).invoke(spawnCodec, retainedInput));
        assertEquals((long) spawnBytes.length, buffer.getMethod("readIndex").invoke(retainedInput));
        assertEquals(9, spawn.getRecordComponents().length);
        byteBuf.getMethod("release").invoke(spawnInput);
        byteBuf.getMethod("release").invoke(spawnOutput);
    }

    private static byte[] bytes(Class<?> byteBuf, Object buffer) throws Exception {
        final byte[] result = new byte[(int) byteBuf.getMethod("readableBytes").invoke(buffer)];
        byteBuf.getMethod("readBytes", byte[].class).invoke(buffer, (Object) result);
        return result;
    }

    private static URLClassLoader loader(String name) throws Exception {
        final Path directory = Path.of(System.getenv("NIGHTSTORM_CODEC_CLASSPATHS"));
        final var paths = Files.readString(directory.resolve(name + ".txt")).strip().split(java.io.File.pathSeparator);
        final URL[] urls = new URL[paths.length];
        for (int i = 0; i < paths.length; i++) urls[i] = Path.of(paths[i]).toUri().toURL();
        return new URLClassLoader(urls, ClassLoader.getPlatformClassLoader());
    }
}
