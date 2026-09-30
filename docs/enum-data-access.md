# Enum data accessor relocation

The compatibility installer wraps scalar JSON getters inside enum generators. It retains each upstream getter, optional public member access, and integer mask from the generator's source. No enum name, color value, Minecraft version, or destination class is configured.

Baseline generation calls the original getter and records its values by enum identity. If the getter disappears in the target, `EnumDataAccess` searches vanilla for public static tables whose record components cover those same identities. Exactly one independent table must reproduce the complete baseline profile. Direct static aliases are traced through bytecode to their original field. Separate tables with identical profiles are ambiguous and stop generation.

The target values are read from that vanilla table, rather than copied from the baseline. The discovered owner and field are saved alongside the profiles in `.nightstorm/enum-data-accessors.json`. Subsequent generations follow the saved source and therefore pick up changed values without another fingerprint match. A missing saved source stops generation instead of selecting a replacement silently.

Loom supplies the merged client/server jar because presentation data can move into client classes. The generator reads those static data tables without launching the Minecraft client. Packet scanning uses the same jar.

This handles scalar getter data moved into enum-keyed record tables. It does not infer arbitrary computations or find a new table when its values and location change simultaneously. Missing baseline identities, missing matches, and ambiguous matches produce errors. Existing block/registry compatibility bridges and data normalization rules remain separate.

`EnumDataAccessTest` covers masks, object member access, unrelated enum identities, direct aliases, changed values after learning, ambiguity, and absent/mismatched baselines. The normal prepare/finalize scripts exercise it against the selected Minecraft version.
