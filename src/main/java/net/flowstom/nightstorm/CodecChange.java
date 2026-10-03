package net.flowstom.nightstorm;

import java.util.Map;

/** A proven codec expression parameterized by the retained source's enum representation when necessary. */
record CodecChange(String carrier, String expression, Map<String, Integer> enumIds, boolean nullable) {
    CodecChange { enumIds = Map.copyOf(enumIds); }
    static CodecChange functions(CodecFunctions.Adapter adapter) {
        return new CodecChange(adapter.carrier(), adapter.expression(), Map.of(), false);
    }
    static CodecChange bool(int falseId, int trueId) {
        return new CodecChange("boolean", "NetworkBuffer.VAR_INT.transform((Integer id) -> switch (id) {case "
                + falseId + " -> false; case " + trueId
                + " -> true; default -> throw new IllegalArgumentException(\"Unknown enum id: \" + id);}, (Boolean value) -> value ? "
                + trueId + " : " + falseId + ")", Map.of(), false);
    }
    static CodecChange enumeration(String networkType, Map<String, Integer> ids, boolean nullable) {
        var source = new StringBuilder(networkType + ".transform((Integer id) -> ");
        if (nullable) source.append("id == null ? null : ");
        source.append("switch (id) {");
        ids.entrySet().stream().sorted(Map.Entry.comparingByValue()).forEach(entry -> source.append("case ").append(entry.getValue())
                .append(" -> $TYPE$.").append(entry.getKey()).append(";"));
        source.append("default -> throw new IllegalArgumentException(\"Unknown enum id: \" + id);}, ($TYPE$ value) -> ");
        if (nullable) source.append("value == null ? null : ");
        source.append("switch (value) {");
        ids.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> source.append("case ").append(entry.getKey()).append(" -> ").append(entry.getValue()).append(";"));
        return new CodecChange("", source.append("})").toString(), ids, nullable);
    }
}
