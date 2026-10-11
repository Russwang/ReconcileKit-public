package dev.reconcilekit.importbatch;

import dev.reconcilekit.api.ApiException;
import dev.reconcilekit.api.JsonInput;
import dev.reconcilekit.api.Money;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class ImportParser {
    private static final Set<String> ENVELOPE = Set.of("source", "rows");
    private static final Set<String> INVOICE = Set.of("source", "external_invoice_id", "amount", "currency", "issued_on");
    private static final Set<String> PAYMENT = Set.of("source", "external_payment_id", "amount", "currency", "received_on", "raw_reference", "target_invoice_source", "target_external_invoice_id");
    private final String authoritativeInvoiceSource;
    public ImportParser(@Value("${reconcilekit.authoritative-invoice-source:demo-invoices}") String source) {
        authoritativeInvoiceSource = JsonInput.identity(source, "configured invoice source");
    }
    public ImportCommand parse(ImportCommand.Kind kind, JsonNode body, String requestId) {
        if (kind == null) throw ApiException.structure("Missing import type");
        JsonInput.object(body, ENVELOPE);
        String source = JsonInput.id(body, "source");
        JsonNode inputRows = body.get("rows");
        if (inputRows == null || !inputRows.isArray()) throw ApiException.structure("rows must be an array");
        int total = inputRows.size();
        var attempt = new ImportAttempt(UUID.randomUUID(), kind, source, total, ImportContentHash.rejected(kind, body), requestId);
        if (total < 1 || total > 1000) throw rejection(attempt, ApiException.invalid("rows must contain 1–1000 records"), 0);
        if (kind == ImportCommand.Kind.INVOICES && !authoritativeInvoiceSource.equals(source))
            throw rejection(attempt, ApiException.invalid("Invoice source must match the configured authoritative source"), 0);
        var rows = new ArrayList<ImportCommand.Row>(total);
        var firstRows = new HashMap<String, ImportCommand.Row>();
        var errors = new ArrayList<ImportReport.RowError>();
        ApiException primary = null;
        for (int index = 0; index < total; index++) {
            try {
                var row = row(kind, source, inputRows.get(index), index + 1);
                var first = firstRows.putIfAbsent(row.externalId(), row);
                if (first != null && !first.sameBusiness(row)) throw ApiException.conflict("Same source identity has different business content within the batch");
                rows.add(row);
            } catch (ApiException error) {
                if (primary == null) primary = error;
                errors.add(new ImportReport.RowError(index + 1, error.code(), error.getMessage()));
            }
        }
        if (primary != null) throw new ImportRejection(attempt, primary.status(), primary.code(), "Import batch rejected", errors);
        return new ImportCommand(attempt.attemptId(), kind, source, rows, total, ImportContentHash.normalized(kind, source, rows), requestId);
    }
    /** Internal callers also pass through the same immutable-input rules before any write SQL. */
    public ImportCommand canonicalize(ImportCommand command) {
        if (command == null || command.kind() == null || command.attemptId() == null)
            throw ApiException.structure("Missing import command");
        String source = JsonInput.identity(command.source(), "source");
        String requestId = JsonInput.storageText(command.requestId(), "request_id");
        if (requestId.length() < 1 || requestId.length() > 100) throw ApiException.structure("request_id must be 1–100 characters");
        if (command.rows().size() < 1 || command.rows().size() > 1000) throw ApiException.invalid("rows must contain 1–1000 records");
        if (command.total() != command.rows().size()) throw ApiException.structure("Import total must match the input rows");
        if (command.kind() == ImportCommand.Kind.INVOICES && !authoritativeInvoiceSource.equals(source))
            throw ApiException.invalid("Invoice source must match the configured authoritative source");
        var rows = new ArrayList<ImportCommand.Row>(command.total());
        var firstRows = new HashMap<String, ImportCommand.Row>();
        for (int index = 0; index < command.rows().size(); index++) {
            var input = command.rows().get(index);
            if (input == null || input.sourceRow() != index + 1) throw ApiException.structure("Invalid source row metadata");
            if (!source.equals(JsonInput.identity(input.source(), "source"))) throw ApiException.invalid("Row source must match batch source");
            String externalId = JsonInput.identity(input.externalId(), "external_id");
            if (input.amount() == null) throw ApiException.structure("Missing amount");
            var amount = Money.parse(input.amount().toPlainString());
            if (!"GBP".equals(JsonInput.storageText(input.currency(), "currency"))) throw ApiException.invalid("Only GBP is supported");
            if (input.occurredOn() == null) throw ApiException.structure("Missing date");
            if (input.occurredOn().getYear() < 1 || input.occurredOn().getYear() > 9999) throw ApiException.invalid("Date must use a year from 0001 to 9999");
            String raw = input.rawReference() == null ? null : JsonInput.storageText(input.rawReference(), "raw_reference");
            String targetSource = input.targetInvoiceSource(), targetId = input.targetExternalInvoiceId();
            if ((targetSource == null) != (targetId == null)) throw ApiException.structure("Both target invoice fields must be present or both null");
            if (targetSource != null) {
                targetSource = JsonInput.identity(targetSource, "target_invoice_source"); targetId = JsonInput.identity(targetId, "target_external_invoice_id");
            }
            if (command.kind() == ImportCommand.Kind.INVOICES && (raw != null || targetSource != null)) throw ApiException.structure("Invoice input cannot contain payment fields");
            var row = new ImportCommand.Row(index + 1, source, externalId, amount, "GBP", input.occurredOn(), raw, targetSource, targetId);
            var first = firstRows.putIfAbsent(externalId, row);
            if (first != null && !first.sameBusiness(row)) throw ApiException.conflict("Same source identity has different business content within the batch");
            rows.add(row);
        }
        return new ImportCommand(command.attemptId(), command.kind(), source, rows, command.total(), ImportContentHash.normalized(command.kind(), source, rows), requestId);
    }
    private ImportCommand.Row row(ImportCommand.Kind kind, String source, JsonNode input, int sourceRow) {
        JsonInput.object(input, kind == ImportCommand.Kind.INVOICES ? INVOICE : PAYMENT);
        String rowSource = JsonInput.id(input, "source");
        if (!source.equals(rowSource)) throw ApiException.invalid("Row source must match batch source");
        String externalId = JsonInput.id(input, kind == ImportCommand.Kind.INVOICES ? "external_invoice_id" : "external_payment_id");
        var amount = Money.parse(JsonInput.text(input, "amount"));
        String currency = JsonInput.text(input, "currency");
        if (!"GBP".equals(currency)) throw ApiException.invalid("Only GBP is supported");
        var occurredOn = JsonInput.date(input, kind == ImportCommand.Kind.INVOICES ? "issued_on" : "received_on");
        String raw = null, targetSource = null, targetId = null;
        if (kind == ImportCommand.Kind.PAYMENTS) {
            raw = optionalText(input, "raw_reference");
            targetSource = optionalText(input, "target_invoice_source"); targetId = optionalText(input, "target_external_invoice_id");
            if ((targetSource == null) != (targetId == null)) throw ApiException.structure("Both target invoice fields must be present or both null");
            if (targetSource != null) {
                targetSource = JsonInput.identity(targetSource, "target_invoice_source");
                targetId = JsonInput.identity(targetId, "target_external_invoice_id");
            }
        }
        return new ImportCommand.Row(sourceRow, source, externalId, amount, currency, occurredOn, raw, targetSource, targetId);
    }
    private static String optionalText(JsonNode input, String field) {
        JsonNode value = input.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw ApiException.structure(field + " must be a string or null");
        return JsonInput.storageText(value.asString(), field);
    }
    private static ImportRejection rejection(ImportAttempt attempt, ApiException error, int row) {
        return new ImportRejection(attempt, error.status(), error.code(), error.getMessage(), List.of(new ImportReport.RowError(row, error.code(), error.getMessage())));
    }
}
