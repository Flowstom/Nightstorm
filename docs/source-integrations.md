# Source and data integration

`install-integrations` locates declared generator and registry types, parses their Java syntax, and inserts hooks using actual loop iterables, accumulators, and declared output paths. Formatting, local variable names, completion-log text, and filenames do not select an insertion point. Enum JSON getter wrapping uses the same Java syntax tree instead of a regular-expression installer. Repeating installation leaves source unchanged.

`scripts/data-generator.gradle` selects Loom's merged jar configuration through the plugin's own class loader. During generator execution it identifies the vanilla jar on the resolved runtime classpath by its contents and reports the exact path. Prepare/finalize no longer assume a Loom cache directory or artifact filename. The data-generator revision remains pinned for reproducibility.

The registry lookup bridge discovers the unique public static zero-argument factory returning `HolderLookup.Provider`. It stops on ambiguity; there is no list of replacement factory names. The installer still recognizes the known generator call sites at that API boundary. The block-state bridge still explicitly supports `blocksMotion` and `isSolid`.

`DataShapeScanner` discovers component and attribute registrations from source and writes `.nightstorm/data-shapes.json`. For supported list-backed record codecs, it verifies that the encoder's field order agrees with the decoding constructor. The data normalizer uses that order and scalar type instead of a configured pot-side order. It accepts primitive leaves and single-property primitive wrappers; this is a bounded structural conversion, not proof of arbitrary wrapper semantics.

Three explicit JSON conversions remain: `palette_id` to the basename stored as `asset_name`, `destroy_on_use` to `explodes`, and the `append` modifier's `argument` unwrapping. Replacing these requires semantic evidence for the upstream/retained codecs. Java/Gradle/Minecraft API vocabulary, the retained registration types, version-catalog keys, Gradle dependency edits, Javadoc edits, and the source palette-width constant are also explicit integration boundaries. The palette width itself is derived from generated state IDs rather than a fixed number.

Regression tests exercise renamed files and locals, formatting changes, repeated installation, unrelated record layouts, mismatched scalar data, and contradictory constructors. The workflow runs the engine tests before planning an update.
