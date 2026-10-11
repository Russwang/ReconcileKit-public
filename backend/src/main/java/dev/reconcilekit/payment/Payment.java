package dev.reconcilekit.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import jakarta.persistence.*;

/** Immutable imported payment data; raw reference never supplies an inferred target. */
@Entity
@Table(name = "payments")
public class Payment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 100) private String source;
    @Column(name = "external_payment_id", nullable = false, length = 100) private String externalPaymentId;
    @Column(nullable = false, precision = 19, scale = 2) private BigDecimal amount;
    @Column(nullable = false, length = 3) private String currency;
    @Column(name = "received_on", nullable = false) private LocalDate receivedOn;
    @Column(name = "raw_reference", columnDefinition = "text") private String rawReference;
    @Column(name = "target_invoice_source", length = 100) private String targetInvoiceSource;
    @Column(name = "target_external_invoice_id", length = 100) private String targetExternalInvoiceId;
    @Column(name = "first_batch_id", nullable = false) private UUID firstBatchId;
    @Column(name = "source_row", nullable = false) private Integer sourceRow;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected Payment() {}

    public Payment(String source, String externalPaymentId, BigDecimal amount, String currency,
                   LocalDate receivedOn, String rawReference, String targetInvoiceSource,
                   String targetExternalInvoiceId, UUID firstBatchId, int sourceRow) {
        this.source = source;
        this.externalPaymentId = externalPaymentId;
        this.amount = amount;
        this.currency = currency;
        this.receivedOn = receivedOn;
        this.rawReference = rawReference;
        this.targetInvoiceSource = targetInvoiceSource;
        this.targetExternalInvoiceId = targetExternalInvoiceId;
        this.firstBatchId = firstBatchId;
        this.sourceRow = sourceRow;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getSource() { return source; }
    public String getExternalPaymentId() { return externalPaymentId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public LocalDate getReceivedOn() { return receivedOn; }
    public String getRawReference() { return rawReference; }
    public String getTargetInvoiceSource() { return targetInvoiceSource; }
    public String getTargetExternalInvoiceId() { return targetExternalInvoiceId; }
    public UUID getFirstBatchId() { return firstBatchId; }
    public Integer getSourceRow() { return sourceRow; }
    public Instant getCreatedAt() { return createdAt; }
}
