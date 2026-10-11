package dev.reconcilekit.invoice;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import jakarta.persistence.*;

/** Immutable imported invoice data. Balances and status are derived separately. */
@Entity
@Table(name = "invoices")
public class Invoice {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 100) private String source;
    @Column(name = "external_invoice_id", nullable = false, length = 100) private String externalInvoiceId;
    @Column(nullable = false, precision = 19, scale = 2) private BigDecimal amount;
    @Column(nullable = false, length = 3) private String currency;
    @Column(name = "issued_on", nullable = false) private LocalDate issuedOn;
    @Column(name = "first_batch_id", nullable = false) private UUID firstBatchId;
    @Column(name = "source_row", nullable = false) private Integer sourceRow;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected Invoice() {}

    public Invoice(String source, String externalInvoiceId, BigDecimal amount, String currency,
                   LocalDate issuedOn, UUID firstBatchId, int sourceRow) {
        this.source = source;
        this.externalInvoiceId = externalInvoiceId;
        this.amount = amount;
        this.currency = currency;
        this.issuedOn = issuedOn;
        this.firstBatchId = firstBatchId;
        this.sourceRow = sourceRow;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getSource() { return source; }
    public String getExternalInvoiceId() { return externalInvoiceId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public LocalDate getIssuedOn() { return issuedOn; }
    public UUID getFirstBatchId() { return firstBatchId; }
    public Integer getSourceRow() { return sourceRow; }
    public Instant getCreatedAt() { return createdAt; }
}
