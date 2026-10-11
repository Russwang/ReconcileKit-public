package dev.reconcilekit.importbatch;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Validated immutable business input; sourceRow only describes the first input occurrence. */
public record ImportCommand(UUID attemptId, Kind kind, String source, List<Row> rows,
        int total, String contentHash, String requestId) {
    public ImportCommand { rows = List.copyOf(rows); }
    public enum Kind { INVOICES, PAYMENTS }
    public record Row(int sourceRow, String source, String externalId, BigDecimal amount,
            String currency, LocalDate occurredOn, String rawReference,
            String targetInvoiceSource, String targetExternalInvoiceId) {
        public boolean sameBusiness(Row other) {
            return source.equals(other.source) && externalId.equals(other.externalId)
                    && amount.compareTo(other.amount) == 0 && currency.equals(other.currency)
                    && occurredOn.equals(other.occurredOn)
                    && Objects.equals(rawReference, other.rawReference)
                    && Objects.equals(targetInvoiceSource, other.targetInvoiceSource)
                    && Objects.equals(targetExternalInvoiceId, other.targetExternalInvoiceId);
        }
    }
}
