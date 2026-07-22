#!/usr/bin/env bash
set -euo pipefail

data_generator_root=${1:?usage: install-data-generator-compat.sh <data-generator-root>}
nightstorm_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
generator_package="$data_generator_root/DataGenerator/src/main/java/net/minestom/generators"

cp "$nightstorm_root/templates/data-generator/MinecraftCompatibility.java" "$generator_package/MinecraftCompatibility.java"
cp "$nightstorm_root/templates/data-generator/NightstormDataNormalizer.java" "$generator_package/NightstormDataNormalizer.java"

material_generator="$generator_package/MaterialGenerator.java"
perl -0pi -e 's/VanillaRegistries\.(?:createWorldLookup|createLookup)\(\)/MinecraftCompatibility.vanillaLookup()/g' "$material_generator"

block_generator="$generator_package/BlockGenerator.java"
perl -0pi -e 's/blockState\.(?:blocksMotion|isSolid)\(\)/MinecraftCompatibility.blocksMotion(blockState)/g' "$block_generator"

data_gen="$data_generator_root/DataGenerator/src/main/java/net/minestom/datagen/DataGen.java"
if ! grep --quiet 'import net.minestom.generators.NightstormDataNormalizer;' "$data_gen"; then
  perl -0pi -e 's/import net\.minestom\.generators\.tags\.GenericTagGenerator;/import net.minestom.generators.NightstormDataNormalizer;\nimport net.minestom.generators.tags.GenericTagGenerator;/' "$data_gen"
fi
if ! grep --quiet 'NightstormDataNormalizer.normalizeFromEnvironment' "$data_gen"; then
  perl -0pi -e 's/(        LOGGER\.info\("Generation done!"\);)/        NightstormDataNormalizer.normalizeFromEnvironment(OUTPUT);\n$1/' "$data_gen"
fi

grep --quiet 'MinecraftCompatibility.vanillaLookup()' "$material_generator"
grep --quiet 'MinecraftCompatibility.blocksMotion(blockState)' "$block_generator"
grep --quiet 'NightstormDataNormalizer.normalizeFromEnvironment(OUTPUT)' "$data_gen"
