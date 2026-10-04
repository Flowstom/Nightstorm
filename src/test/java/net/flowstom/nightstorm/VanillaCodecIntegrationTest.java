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
            verifyStructuralAdapters(vanilla, generated, registries);
        } finally {
            System.setOut(stdout);
            System.setErr(stderr);
        }
    }

    private static void verifyStructuralAdapters(ClassLoader vanilla, ClassLoader generated, Object registries) throws Exception {
        final var point = generated.loadClass("net.minestom.server.coordinate.Point");
        final var vector = generated.loadClass("net.minestom.server.coordinate.Vec");
        final Object position = vector.getConstructor(double.class, double.class, double.class).newInstance(12d, -3d, 45d);
        final var sign = generated.loadClass("net.minestom.server.network.packet.client.play.ClientUpdateSignPacket");
        final var nativeSign = vanilla.loadClass("net.minecraft.network.protocol.game.ServerboundSignUpdatePacket");
        final var blockPos = vanilla.loadClass("net.minecraft.core.BlockPos");
        final var slot = vanilla.loadClass("net.minecraft.world.level.block.entity.SignTextSlot");
        final var lines = java.util.List.of("first", "žluťoučký", "🙂", "fourth");
        for (boolean front : new boolean[]{false, true}) {
            final Object retained = sign.getConstructor(point, boolean.class, java.util.List.class).newInstance(position, front, lines);
            final Object nativeValue = nativeSign.getConstructor(blockPos, java.util.List.class, slot).newInstance(
                    blockPos.getConstructor(int.class, int.class, int.class).newInstance(12, -3, 45), lines,
                    slot.getField(front ? "FRONT" : "BACK").get(null));
            verifyPair(vanilla, generated, registries, sign, "SERIALIZER", retained, nativeSign, nativeValue);
        }

        final var sync = generated.loadClass("net.minestom.server.network.packet.server.play.EntityPositionSyncPacket");
        final var choice = generated.loadClass(sync.getName() + "$WireChoice");
        final var linear = generated.loadClass(sync.getName() + "$WireChoiceCase0");
        final var stepped = generated.loadClass(sync.getName() + "$WireChoiceCase1");
        final var step = generated.loadClass(sync.getName() + "$WirePayload2");
        final var nativeSync = vanilla.loadClass("net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket");
        final var nativePath = vanilla.loadClass("net.minecraft.world.entity.PositionPath");
        final var nativeVector = vanilla.loadClass("net.minecraft.world.phys.Vec3");
        final var nativeStep = vanilla.loadClass("net.minecraft.world.entity.PositionStep");
        final Object nativePosition = nativeVector.getConstructor(double.class, double.class, double.class).newInstance(12d, -3d, 45d);
        final Object otherPosition = vector.getConstructor(double.class, double.class, double.class).newInstance(-5.5d, 60d, 0.25d);
        final Object otherNativePosition = nativeVector.getConstructor(double.class, double.class, double.class).newInstance(-5.5d, 60d, 0.25d);
        final Object[] choices = {
                linear.getConstructor(point).newInstance(position),
                stepped.getConstructor(java.util.List.class).newInstance(java.util.List.of(
                        step.getConstructor(point, int.class).newInstance(position, 3),
                        step.getConstructor(point, int.class).newInstance(otherPosition, 19)))
        };
        final Object[] nativeChoices = {
                vanilla.loadClass(nativePath.getName() + "$Linear").getConstructor(nativeVector).newInstance(nativePosition),
                vanilla.loadClass(nativePath.getName() + "$Stepped").getConstructor(java.util.List.class).newInstance(java.util.List.of(
                        nativeStep.getConstructor(nativeVector, int.class).newInstance(nativePosition, 3),
                        nativeStep.getConstructor(nativeVector, int.class).newInstance(otherNativePosition, 19)))
        };
        for (int i = 0; i < choices.length; i++) {
            final Object retained = sync.getConstructor(int.class, choice, float.class, float.class, boolean.class)
                    .newInstance(12345, choices[i], 89.5f, -12f, true);
            final Object nativeValue = nativeSync.getConstructor(int.class, nativePath, float.class, float.class, boolean.class)
                    .newInstance(12345, nativeChoices[i], 89.5f, -12f, true);
            verifyPair(vanilla, generated, registries, sync, "SERIALIZER", retained, nativeSync, nativeValue);
        }
        final Object emptyPath = stepped.getConstructor(java.util.List.class).newInstance(java.util.List.of());
        final Object invalid = sync.getConstructor(int.class, choice, float.class, float.class, boolean.class)
                .newInstance(1, emptyPath, 0f, 0f, false);
        final var generatedBuffer = generated.loadClass("net.minestom.server.network.NetworkBuffer");
        final var generatedType = generated.loadClass("net.minestom.server.network.NetworkBuffer$Type");
        final var rejection = assertThrows(java.lang.reflect.InvocationTargetException.class, () ->
                generatedBuffer.getMethod("makeArray", generatedType, Object.class).invoke(null, sync.getField("SERIALIZER").get(null), invalid));
        assertInstanceOf(IllegalArgumentException.class, rejection.getCause());

        final var mapping = generated.loadClass("net.minestom.server.network.packet.server.play.AdvancementsPacket$AdvancementMapping");
        final var advancement = generated.loadClass("net.minestom.server.network.packet.server.play.AdvancementsPacket$Advancement");
        final var display = generated.loadClass("net.minestom.server.network.packet.server.play.AdvancementsPacket$DisplayData");
        final Object value = advancement.getConstructor(String.class, display, java.util.List.class, boolean.class)
                .newInstance(null, null, java.util.List.of(), false);
        final Object retained = mapping.getConstructor(String.class, advancement, float.class, float.class)
                .newInstance("test:empty", value, 12.5f, -4.75f);
        final var nativeAdvancement = vanilla.loadClass("net.minecraft.advancements.Advancement");
        final var rewards = vanilla.loadClass("net.minecraft.advancements.AdvancementRewards");
        final var requirements = vanilla.loadClass("net.minecraft.advancements.AdvancementRequirements");
        final Object nativeValue = nativeAdvancement.getConstructor(java.util.Optional.class, java.util.Optional.class,
                        rewards, java.util.Map.class, requirements, boolean.class)
                .newInstance(java.util.Optional.empty(), java.util.Optional.empty(), rewards.getField("EMPTY").get(null),
                        java.util.Map.of(), requirements.getConstructor(java.util.List.class).newInstance(java.util.List.of()), false);
        final var identifier = vanilla.loadClass("net.minecraft.resources.Identifier");
        final var holder = vanilla.loadClass("net.minecraft.advancements.AdvancementHolder");
        final Object nativeHolder = holder.getConstructor(identifier, nativeAdvancement).newInstance(
                identifier.getMethod("parse", String.class).invoke(null, "test:empty"), nativeValue);
        final var positioned = vanilla.loadClass("net.minecraft.network.protocol.game.ClientboundUpdateAdvancementsPacket$PositionedAdvancement");
        final Object decoded = verifyPair(vanilla, generated, registries, mapping, "SERIALIZER", retained, positioned,
                positioned.getConstructor(holder, float.class, float.class).newInstance(nativeHolder, 12.5f, -4.75f));
        assertEquals(12.5f, positioned.getMethod("x").invoke(decoded));
        assertEquals(-4.75f, positioned.getMethod("y").invoke(decoded));
    }

    private static Object verifyPair(ClassLoader vanilla, ClassLoader generated, Object registries,
                                     Class<?> retainedType, String serializerName, Object retained,
                                     Class<?> nativeType, Object nativeValue) throws Exception {
        final var buffer = generated.loadClass("net.minestom.server.network.NetworkBuffer");
        final var type = generated.loadClass("net.minestom.server.network.NetworkBuffer$Type");
        final var byteBuf = vanilla.loadClass("io.netty.buffer.ByteBuf");
        final var unpooled = vanilla.loadClass("io.netty.buffer.Unpooled");
        final var codecType = vanilla.loadClass("net.minecraft.network.codec.StreamCodec");
        final var nativeBuffer = vanilla.loadClass("net.minecraft.network.RegistryFriendlyByteBuf")
                .getConstructor(byteBuf, vanilla.loadClass("net.minecraft.core.RegistryAccess"));
        final Object serializer = retainedType.getField(serializerName).get(null);
        final Object codec = nativeType.getField("STREAM_CODEC").get(null);
        final byte[] payload = (byte[]) buffer.getMethod("makeArray", type, Object.class).invoke(null, serializer, retained);
        final Object input = nativeBuffer.newInstance(unpooled.getMethod("wrappedBuffer", byte[].class).invoke(null, (Object) payload), registries);
        final Object output = nativeBuffer.newInstance(unpooled.getMethod("buffer").invoke(null), registries);
        try {
            final Object decoded = codecType.getMethod("decode", Object.class).invoke(codec, input);
            assertEquals(0, byteBuf.getMethod("readableBytes").invoke(input));
            codecType.getMethod("encode", Object.class, Object.class).invoke(codec, output, decoded);
            assertArrayEquals(payload, bytes(byteBuf, output));
            codecType.getMethod("encode", Object.class, Object.class).invoke(codec, output, nativeValue);
            final byte[] nativePayload = bytes(byteBuf, output);
            assertArrayEquals(payload, nativePayload);
            final Object retainedInput = buffer.getMethod("wrap", byte[].class, int.class, int.class)
                    .invoke(null, nativePayload, 0, nativePayload.length);
            assertEquals(retained, type.getMethod("read", buffer).invoke(serializer, retainedInput));
            assertEquals((long) payload.length, buffer.getMethod("readIndex").invoke(retainedInput));
            return decoded;
        } finally {
            byteBuf.getMethod("release").invoke(input);
            byteBuf.getMethod("release").invoke(output);
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
