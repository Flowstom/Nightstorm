#!/usr/bin/env bash
set -euo pipefail

data_generator_root=${1:?usage: install-registry-sync.sh <data-generator-root> <minestom-root>}
minestom_root=${2:?usage: install-registry-sync.sh <data-generator-root> <minestom-root>}
nightstorm_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)

cp "$nightstorm_root/templates/registry-sync/SynchronizedRegistryGenerator.java" \
  "$data_generator_root/DataGenerator/src/main/java/net/minestom/generators/SynchronizedRegistryGenerator.java"
cp "$nightstorm_root/templates/registry-sync/NightstormRegistryData.java" \
  "$minestom_root/src/main/java/net/minestom/server/registry/NightstormRegistryData.java"

data_gen="$data_generator_root/DataGenerator/src/main/java/net/minestom/datagen/DataGen.java"
if ! grep --quiet 'import net.minestom.generators.SynchronizedRegistryGenerator;' "$data_gen"; then
  perl -0pi -e 's/import net\.minestom\.generators\.tags\.GenericTagGenerator;/import net.minestom.generators.SynchronizedRegistryGenerator;\nimport net.minestom.generators.tags.GenericTagGenerator;/' "$data_gen"
fi
if ! grep --quiet 'generate("synchronized_registries"' "$data_gen"; then
  perl -0pi -e 's/(        for \(var tag : TAG_TYPES\)) /        generate("synchronized_registries", new SynchronizedRegistryGenerator());\n$1 /' "$data_gen"
fi

registries_impl="$minestom_root/src/main/java/net/minestom/server/registry/RegistriesImpl.java"
if ! grep --quiet 'NightstormRegistryData.appendPackets' "$registries_impl"; then
  perl -0pi -e 's/(for \(DynamicRegistry<\?> registry : configurationRegistries\(registries\)\) \{\n            packets\.add\(registry\.registryDataPacket\(registries, excludeVanilla\)\);\n        \})/$1\n        NightstormRegistryData.appendPackets(packets, configurationRegistries(registries), excludeVanilla);/' "$registries_impl"
fi
if ! grep --quiet 'NightstormRegistryData.appendTags' "$registries_impl"; then
  perl -0pi -e 's/(for \(Registry<\?> registry : tagRegistries\(registries\)\) \{\n            entries\.add\(registry\.tagRegistry\(\)\);\n        \})/$1\n        NightstormRegistryData.appendTags(entries, tagRegistries(registries));/' "$registries_impl"
fi

grep --quiet 'generate("synchronized_registries"' "$data_gen"
grep --quiet 'NightstormRegistryData.appendPackets' "$registries_impl"
grep --quiet 'NightstormRegistryData.appendTags' "$registries_impl"
