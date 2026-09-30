# Packet migration pipeline

Nightstorm uses a shared `WireSchema` for new packet generation and generic retained-packet migrations. The schema records constructor component identity separately from serialization order. It recognizes bound composite codecs, unit codecs, and straight-line manual buffer readers/writers whose field bindings agree in both directions. Unsupported codecs retain the existing diagnostic/fallback behavior.

`ConstructorMapping` interprets parameter loads (including wide local slots), numeric constants, enum constants, field assignments and delegation to another constructor in the same class. It rejects branches, arithmetic, arbitrary calls and ambiguous assignments. A compatibility constructor matching the baseline signature can therefore prove defaults, field renames and one-to-many mappings without knowing a packet name or Minecraft version.

`WireMigration` combines those facts into a projection into the target wire schema. It supports field reordering, proven constructor mappings and full-domain `INT`/`VAR_INT` or `LONG`/`VAR_LONG` encoding changes. It does not assume that equal Java types imply interchangeable encodings: narrowing to an unsigned short, for example, requires further work. Enum constants require a discoverable ID table and an encoder referencing that table's integer field.

`RetainedPacketMigrator` first checks source template or straight-line writer bindings against the baseline schema, then generates serializers from projections. Duplicated values and constants are checked on decode so unrepresentable payloads cannot silently change meaning. Added primitive fields without defaults can be consumed for inbound `ClientPacket` records, but their serializers reject encoding before writing any bytes. Outbound primitive additions without proven defaults stop migration and require a source API change.

Direct projections can also update source records for removed fields and supported collection additions, including nested records shared by multiple packets. Removed fields leave the canonical record API; compatibility constructors accept and ignore the obsolete arguments. New collections become accessible record components; legacy constructors supply empty collections. Historical constructors must delegate directly to the canonical record so later migrations can update them safely. Record logic that uses a removed field stops migration. These API changes are included in `.nightstorm/packet-warnings.md` and release notes.

The shared schema recognizes delegated manual readers, UTF strings, generic codec subtypes, enum-keyed factory codecs, optional operations, and maps with separately inferred key/value codecs. Type-erasure casts do not alter wire shape. Codecs stored in dispatch catalog entries are kept separate from the enclosing packet's field sequence. Generated local names avoid names bound inside reused source codec expressions.

Complex retained components reuse a source codec only after Nightstorm matches the packet's writer statements to that component type's `NetworkBuffer.Type`; no Vanilla or Minestom class name participates in the match. New packets use structured records for protocol primitive codecs. A semantic codec whose Java representation cannot be proved across Vanilla and Minestom uses the existing opaque payload representation and emits a warning instead of consulting a type-alias table.

Adapter provenance is saved by packet and nested component path in `.nightstorm/wire-adapters.json`. Subsequent migrations compose with the saved projection rather than assuming the retained source record acquired every upstream field. When a source API is synchronized, its saved schema advances to the target. The metadata and generated Java changes commit and roll back together through `SourceTransaction`. Repeated applications of the same migration are idempotent.

## Scope of this increment

The teleport-confirm and particle-specific matchers and serializers have been removed. Their changes now exercise field addition, constructor-derived repeated arguments/defaults, reordering and integer encoding changes. Tests also use unrelated packet and field names, a nonzero enum default, invalid bindings, unsupported constructor bodies, narrowing encodings and successive schema migrations.

This is not an interpreter for arbitrary codecs or Minecraft gameplay. Complex optional/dispatch/nested migrations still use the existing specialized rules where available. Source API expansion is limited to direct projections with supported collection additions and removals. In particular, the retained teleport API still exposes only its ID, and the retained particle API cannot express independent axis speeds or arbitrary randomization modes. Constructor inference proves compatibility mappings, not new listener behavior. The separate data-accessor resolver is described in [enum-data-access.md](enum-data-access.md).

## Validation

Run the self-contained regression suite with:

```sh
./gradlew test
```

`VanillaCodecIntegrationTest` additionally compares generated serializers against the real vanilla encoder/decoder, including field values, byte-for-byte re-encoding and full payload consumption. It covers particles, a nonzero teleport confirmation, a nonempty transfer-properties map, and registry-backed spawn data after seed removal. This opt-in fixture targets the upstream changes that motivated the generic implementation.

To run it, prepare/build the generated source and data-generator projects using the normal update scripts. Create a directory containing `vanilla.txt` and `generated.txt`, each with its project's `main.runtimeClasspath` as a single platform-separated line. `scripts/codec-test-classpath.gradle` exports this as `NIGHTSTORM_CP=...`:

```sh
# Run in the data-generator checkout:
./gradlew -I /path/to/Nightstorm/scripts/codec-test-classpath.gradle \
  :DataGenerator:nightstormClasspath --no-configuration-cache --no-daemon

# Run in the generated Minestom checkout:
./gradlew -I /path/to/Nightstorm/scripts/codec-test-classpath.gradle \
  :nightstormClasspath --no-configuration-cache \
  -Dorg.gradle.unsafe.isolated-projects=false --no-daemon

# Run in Nightstorm after saving the two classpath values:
NIGHTSTORM_CODEC_CLASSPATHS=/path/to/classpath-directory \
  ./gradlew test --rerun-tasks
```

The diagnostic task disables configuration caching because it resolves the runtime classpath during task execution. The production workflow continues to use its normal Gradle settings. No Minecraft jars or generated binaries are checked into this repository.
