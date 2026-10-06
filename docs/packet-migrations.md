# Packet migration pipeline

Nightstorm uses a shared `WireSchema` for new packet generation and generic retained-packet migrations. The schema records constructor component identity separately from serialization order. It recognizes bound composite codecs, unit codecs, and manual buffer readers/writers whose field bindings agree in both directions. `WireManual` also executes bounded constant array loops, proving the element count, order, and codec in both directions. Data-dependent branches and arbitrary calls remain unsupported. Unsupported codecs retain the existing diagnostic/fallback behavior.

`ConstructorMapping` interprets parameter loads (including wide local slots), numeric constants, enum constants, field assignments and delegation to another constructor in the same class. It rejects branches, arithmetic, arbitrary calls and ambiguous assignments. A compatibility constructor matching the baseline signature can therefore prove defaults, field renames and one-to-many mappings without knowing a packet name or Minecraft version.

`WireMigration` combines those facts into a projection into the target wire schema. It supports field reordering, proven constructor mappings and full-domain `INT`/`VAR_INT` or `LONG`/`VAR_LONG` encoding changes. It does not assume that equal Java types imply interchangeable encodings: narrowing to an unsigned short, for example, requires further work. Enum constants require a discoverable ID table and an encoder referencing that table's integer field.

`RetainedPacketMigrator` first checks source template or straight-line writer bindings against the baseline schema, then generates serializers from projections. Duplicated values and constants are checked on decode so unrepresentable payloads cannot silently change meaning. Added primitive fields without defaults can be consumed for inbound `ClientPacket` records, but their serializers reject encoding before writing any bytes. Outbound primitive additions without proven defaults stop migration and require a source API change.

Direct projections can also update source records for removed fields and supported collection additions, including nested records shared by multiple packets. Removed fields leave the canonical record API; compatibility constructors accept and ignore the obsolete arguments. New collections become accessible record components; legacy constructors supply empty collections. Historical constructors must delegate directly to the canonical record so later migrations can update them safely. Record logic that uses a removed field stops migration. These API changes are included in `.nightstorm/packet-warnings.md` and release notes.

The shared schema recognizes delegated manual readers, UTF strings, generic codec subtypes, enum-keyed factory codecs, optional operations, and maps with separately inferred key/value codecs. Type-erasure casts do not alter wire shape. Codecs stored in dispatch catalog entries are kept separate from the enclosing packet's field sequence. Generated local names avoid names bound inside reused source codec expressions.

Complex retained components reuse a source codec only after Nightstorm matches the packet's writer statements to that component type's `NetworkBuffer.Type`; no Vanilla or Minestom class name participates in the match. New packets use structured records for protocol primitive codecs. A semantic codec whose Java representation cannot be proved across Vanilla and Minestom uses the existing opaque payload representation and emits a warning instead of consulting a type-alias table.

Adapter provenance is saved by packet and nested component path in `.nightstorm/wire-adapters.json`. Subsequent migrations compose with the saved projection rather than assuming the retained source record acquired every upstream field. When a source API is synchronized, its saved schema advances to the target. The metadata and generated Java changes commit and roll back together through `SourceTransaction`. Repeated applications of the same migration are idempotent.

## Inferred codec changes

Portable JDK carrier codecs use one shared `CODEC_REWRITE` path. `CodecFunctions` verifies a value factory and inverse accessor around matching buffer operations in both directions. This includes changing a bit set between long-array and byte-array storage, without a bit-set-specific serializer. Unrelated carrier fixtures exercise the same implementation. Enum-to-optional changes use the shared codec-expression renderer after checking the upstream optional encoding and deriving every enum ID.

`WIRE_SUFFIX` supports several added scalar fields at once. Defaults come from a compatibility constructor, constant argument provenance in corresponding upstream callers, or a proven behavioral guard. A behavioral boolean default requires a folded handler to match the baseline, or an unchanged unconditional prefix around the new guard; branch polarity alone is insufficient. Conflicting defaults and opaque computations stop migration. The generated decoder checks defaults instead of silently discarding values that the retained API cannot represent. Successive suffix migrations compose and repeat applications are idempotent.

Boolean-to-enum polarity follows the packet accessor into corresponding consumers and forwarding methods. Pure selection methods establish which old boolean branch corresponds to each new enum constant, including compiler-generated switch tables. Enum names and component names do not determine the mapping. Unsupported computations cannot establish a correspondence.

Packet direction comes from the actual `PacketFlow` constructor argument, following static factories where necessary. Packet field prefixes and individual packet-name exceptions are no longer used.

## Structural API changes and limits

The teleport-confirm and particle-specific matchers and serializers have been removed. Their changes now exercise field addition, constructor-derived repeated arguments/defaults, reordering and integer encoding changes. Tests also use unrelated packet and field names, a nonzero enum default, invalid bindings, unsupported constructor bodies, narrowing encodings and successive schema migrations.

The former boolean/string-list adapter now uses `WireMigration`: fixed-length collections and string limits come from the codec, field order comes from accessor bindings, and boolean polarity comes from consumer evidence. Neither field count nor list length selects the migration.

`WireDispatch` proves enum catalog IDs, catalog-to-payload codec bindings, and each variant's selector. It accepts bound composites and mapped carriers whose encoded constructor input is retained unchanged. An unconditional `getFirst` or `getLast` on that input establishes a nonempty-list constraint. `DispatchRenderer` exposes every proved variant through a sealed interface and generated records, including structured list elements. Legacy constructors select the unique compatible unary variant. Unknown tags are rejected, and obsolete values are no longer duplicated into unrelated fields. This is bounded enum dispatch support; arbitrary factories, computed catalogs, and unproved payload codecs stop the structural migration. Unsupported existing dispatch codecs continue through the existing scanner behavior.

`WireFieldMove` discovers scalar fields removed from a nested type and added to a collection element wrapper. It checks baseline tail writes, reader/setter assignments, field identity and type, and JVM defaults for fields without constructor assignments. The source adapter traverses record fields to locate the moved values, adds explicit wrapper components, and retains them even when the nested object is absent. Compatibility constructors derive values from the old nested API or the proved baseline defaults. Record-copy methods preserve explicit wrapper values. This currently supports one direct collection component, an unchanged element codec, scalar suffix fields, and record paths up to sixteen levels; conditional scalar encodings and arbitrary object graphs are unsupported.

Dispatch and moved-field adapters save their schema and source hashes in `.nightstorm/structural-adapters.json`. Repetition is idempotent; source edits or a changed structural catalog require a new API projection instead of silently overwriting an existing adapter. The metadata participates in the same source transaction as generated Java.

Source API expansion covers the proved projections and structural changes above. The retained teleport API still exposes only its ID, and the retained particle API cannot express independent axis speeds or arbitrary randomization modes. Constructor inference proves compatibility mappings, not new listener behavior. A manual buffer record can project onto a composite codec when both sides reduce to the same proved operations, including a removed component. UTF resource keys match whether they are written with a buffer helper or a mapped identifier codec. An enum id stored in a byte matches a varint enum id only when every proved id is in 0..127, the range where those encodings are the same byte. A nullable enum whose null is the byte -1 becomes an optional-varint transform when the target codec is proved to be that encoding; the source value stays the nullable enum. Unproved buffer operations, id tables, or optional encodings still stop the migration. The separate enum data-accessor resolver is described in [enum-data-access.md](enum-data-access.md), and scalar/data limitations in [source-integrations.md](source-integrations.md).

## Validation

Run the self-contained regression suite with:

```sh
./gradlew test
```

`VanillaCodecIntegrationTest` additionally compares generated serializers against the real vanilla encoder/decoder, including field values, byte-for-byte re-encoding and full payload consumption. It covers particles, a nonzero teleport confirmation, a nonempty transfer-properties map, registry-backed spawn data after seed removal, both sign slots with Unicode lines, both position-path variants with nonempty steps, and nonzero moved coordinates with an absent advancement display. Prepare a fresh stream through 26.3-rc-3 before advancing it to the target: historical branches generated with the old specialized adapter do not expose the expanded dispatch API used by this fixture.

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
