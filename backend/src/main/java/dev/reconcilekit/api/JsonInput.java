package dev.reconcilekit.api;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** Type checks precede conversion, so JSON numbers never become money strings. */
public final class JsonInput {
    private JsonInput() {}
    public static void object(JsonNode node, Set<String> allowed) {
        if (node == null || !node.isObject()) throw ApiException.structure("Expected an object");
        for (String key : node.propertyNames()) if (!allowed.contains(key)) throw ApiException.structure("Unknown field: " + key);
    }
    public static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) throw ApiException.structure("Missing field: " + field);
        if (!value.isTextual()) throw ApiException.structure(field + " must be a string");
        return storageText(value.asString(), field);
    }
    public static String id(JsonNode node, String field) { return identity(text(node, field), field); }
    public static String identity(String value, String field) {
        String trimmed = storageText(value, field).strip();
        int length = trimmed.codePointCount(0, trimmed.length());
        if (length < 1 || length > 100) throw ApiException.invalid(field + " must be 1–100 characters");
        return trimmed;
    }
    public static long positiveId(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() <= 0)
            throw ApiException.structure(field + " must be a positive integer");
        return value.asLong();
    }
    public static LocalDate date(JsonNode node, String field) {
        String value = text(node, field);
        if (!value.matches("\\d{4}-\\d{2}-\\d{2}")) throw ApiException.invalid(field + " must be YYYY-MM-DD");
        try {
            LocalDate parsed = LocalDate.parse(value);
            if (parsed.getYear() < 1) throw ApiException.invalid(field + " must use a year from 0001 to 9999");
            return parsed;
        }
        catch (DateTimeParseException e) { throw ApiException.invalid("Invalid calendar date: " + field); }
    }
    /** PostgreSQL text cannot represent NUL; invalid Unicode must never be silently replaced. */
    public static String storageText(String value, String field) {
        if (value == null) throw ApiException.structure("Missing field: " + field);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == 0) throw ApiException.invalid(field + " contains unsupported text");
            if (Character.isHighSurrogate(character)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1)))
                    throw ApiException.invalid(field + " contains invalid Unicode");
                index++;
            } else if (Character.isLowSurrogate(character)) throw ApiException.invalid(field + " contains invalid Unicode");
        }
        return value;
    }
}
