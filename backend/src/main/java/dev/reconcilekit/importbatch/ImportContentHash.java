package dev.reconcilekit.importbatch;

import dev.reconcilekit.api.Money;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** SHA-256 trace metadata, never the source identity or the deduplication decision. */
final class ImportContentHash {
    private ImportContentHash() {}
    interface Encoding { void write(DataOutputStream output) throws IOException; }
    static String normalized(ImportCommand.Kind kind, String source, List<ImportCommand.Row> rows) {
        return hash(output -> {
            text(output, "imports:v1"); text(output, kind.name()); text(output, source);
            output.writeInt(rows.size());
            // Input order and sourceRow are metadata; duplicate multiplicity remains traceable.
            for (var row : rows.stream().sorted(Comparator.comparing(ImportCommand.Row::source).thenComparing(ImportCommand.Row::externalId)).toList()) {
                text(output, row.source()); text(output, row.externalId()); text(output, Money.format(row.amount()));
                text(output, row.currency()); text(output, row.occurredOn().toString());
                nullable(output, row.rawReference()); nullable(output, row.targetInvoiceSource()); nullable(output, row.targetExternalInvoiceId());
            }
        });
    }
    /** Invalid business input cannot be normalized: hash canonical JSON under a distinct scheme. */
    static String rejected(ImportCommand.Kind kind, JsonNode input) {
        return hash(output -> { text(output, "rejected-json:v1"); text(output, kind.name()); json(output, input); });
    }
    private static void json(DataOutputStream output, JsonNode input) throws IOException {
        if (input == null || input.isNull()) { output.writeByte(0); return; }
        if (input.isObject()) {
            output.writeByte(1); var keys = input.propertyNames().stream().sorted().toList(); output.writeInt(keys.size());
            for (String key : keys) { rawText(output, key); json(output, input.get(key)); }
        } else if (input.isArray()) {
            output.writeByte(2); output.writeInt(input.size()); for (JsonNode row : input) json(output, row);
        } else if (input.isTextual()) { output.writeByte(3); rawText(output, input.asString()); }
        else if (input.isNumber()) { output.writeByte(4); rawText(output, input.toString()); }
        else if (input.isBoolean()) { output.writeByte(5); output.writeBoolean(input.asBoolean()); }
        else throw new IllegalArgumentException("Unsupported JSON node");
    }
    private static void nullable(DataOutputStream output, String value) throws IOException {
        output.writeBoolean(value != null); if (value != null) text(output, value);
    }
    private static void text(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8); output.writeInt(bytes.length); output.write(bytes);
    }
    private static void rawText(DataOutputStream output, String value) throws IOException {
        // Preserve even rejected unpaired UTF-16 code units without replacement in trace hashes.
        output.writeInt(value.length()); for (int index = 0; index < value.length(); index++) output.writeChar(value.charAt(index));
    }
    private static String hash(Encoding encoding) {
        try {
            var bytes = new ByteArrayOutputStream(); try (var output = new DataOutputStream(bytes)) { encoding.write(output); }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException error) { throw new IllegalStateException("Cannot hash import input", error); }
    }
}
