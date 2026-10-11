package dev.reconcilekit.allocation;

import java.math.BigDecimal;
import java.util.*;
import dev.reconcilekit.api.*;
import dev.reconcilekit.query.LedgerQueries;
import dev.reconcilekit.reconciliation.AutomaticNotApplied;
import dev.reconcilekit.reconciliation.AutomaticPolicy;
import dev.reconcilekit.transaction.TransactionObserver;
import dev.reconcilekit.transaction.TransactionObserver.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.ObjectMapper;

/** Manual and automatic writes share idempotency, parent locks, fresh sums, audit and response. */
@Service
public class AllocationService {
    public record Applied(UUID operationId, List<Long> allocationIds, LedgerQueries.PaymentView payment, List<LedgerQueries.InvoiceView> invoices) {}
    public record SavedResponse(int status, String body, boolean replayed) {}
    public record AutomaticResult(Applied applied, boolean replayed) {}
    private record Result(Applied applied, SavedResponse response) {}
    private record Stored(String requestHash, UUID operationId, Integer status, String body) {}
    private record AuditAllocation(long allocationId, long paymentId, long invoiceId, String amount, String origin) {}
    private record AuditDetail(List<AuditAllocation> allocations) {}
    private enum Mode {
        MANUAL("MANUAL_ALLOCATION", "MANUAL", "local"), AUTO("AUTO_ALLOCATION", "AUTO", "system");
        final String operationType, origin, actor;
        Mode(String operationType, String origin, String actor) { this.operationType = operationType; this.origin = origin; this.actor = actor; }
    }
    private final JdbcTemplate jdbc;
    private final LedgerQueries queries;
    private final TransactionObserver observer;
    private final ObjectMapper mapper;
    public AllocationService(JdbcTemplate jdbc, LedgerQueries queries, TransactionObserver observer, ObjectMapper mapper) {
        this.jdbc=jdbc; this.queries=queries; this.observer=observer; this.mapper=mapper;
    }

    @Transactional(transactionManager="ledgerTransactions", isolation=Isolation.READ_COMMITTED, rollbackFor=Exception.class)
    public Applied allocate(AllocationCommand command) {
        // Fixture entry uses the same transaction body; every success has audit/idempotency facts.
        return perform(Mode.MANUAL, command, "fixture:" + UUID.randomUUID(), 0).applied();
    }

    @Transactional(transactionManager="ledgerTransactions", isolation=Isolation.READ_COMMITTED, rollbackFor=Exception.class)
    public SavedResponse allocate(AllocationCommand command, String key) {
        return perform(Mode.MANUAL, command, key, 0).response();
    }

    @Transactional(transactionManager="ledgerTransactions", isolation=Isolation.READ_COMMITTED, rollbackFor=Exception.class)
    public AutomaticResult allocateAutomatically(long paymentId) {
        Result result = perform(Mode.AUTO, null, null, paymentId);
        return new AutomaticResult(result.applied(), result.response().replayed());
    }

    private Result perform(Mode mode, AllocationCommand command, String key, long automaticPaymentId) {
        AllocationCommand normalized = null;
        if (mode == Mode.MANUAL) {
            if (command == null) throw ApiException.structure("Missing allocation command");
            normalized = command.canonicalized();
        } else if (automaticPaymentId <= 0) throw ApiException.structure("payment_id must be a positive integer");
        long paymentId = mode == Mode.MANUAL ? normalized.paymentId() : automaticPaymentId;
        String normalizedKey = mode == Mode.MANUAL ? normalizeKey(key) : "auto:v1:" + paymentId;
        String hash = mode == Mode.MANUAL ? AllocationRequestHash.of(normalized) : AllocationRequestHash.automatic(paymentId);
        jdbc.execute("SET LOCAL lock_timeout='2s'");
        UUID operation = UUID.randomUUID();
        Context context = new Context(operation, mode.operationType, jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
        observer.transactionStarted(context);
        observer.beforeIdempotencyClaim(context);
        int claimed = jdbc.update("INSERT INTO idempotency_records(operation_type,key,request_hash,operation_id) VALUES(?,?,?,?) ON CONFLICT(operation_type,key) DO NOTHING",
                mode.operationType, normalizedKey, hash, operation);
        observer.afterIdempotencyClaim(context, claimed == 1);
        if (claimed == 0) {
            // A separate READ COMMITTED statement sees the competitor after its commit/rollback.
            Stored saved = jdbc.queryForObject("SELECT request_hash,operation_id,response_status,response_body FROM idempotency_records WHERE operation_type=? AND key=?",
                    (row, number) -> new Stored(row.getString("request_hash"), row.getObject("operation_id", UUID.class), row.getObject("response_status", Integer.class), row.getString("response_body")), mode.operationType, normalizedKey);
            if (!hash.equals(saved.requestHash())) throw ApiException.conflict("Idempotency key was already used for different content");
            if (saved.status() == null || saved.body() == null) throw new IllegalStateException("Committed idempotency response is incomplete");
            return new Result(mode == Mode.AUTO ? mapper.readValue(saved.body(), Applied.class) : null, new SavedResponse(saved.status(), saved.body(), true));
        }
        lock(context, Parent.PAYMENT, paymentId);
        AllocationCommand lockedCommand = mode == Mode.AUTO ? automaticCommandUnderPaymentLock(paymentId) : normalized;
        for (var target : lockedCommand.allocations()) lock(context, Parent.INVOICE, target.invoiceId());

        // READ COMMITTED takes new SQL snapshots after every parent lock has been acquired.
        var currentPayment = queries.payment(paymentId);
        BigDecimal paymentRemaining = new BigDecimal(currentPayment.remaining());
        var balances = lockedCommand.allocations().stream()
                .map(target -> new InvoiceBalance(target.invoiceId(), new BigDecimal(queries.invoice(target.invoiceId()).remaining())))
                .toList();
        observer.afterBalancesRead(context, paymentId, paymentRemaining, balances);
        if (mode == Mode.AUTO) {
            var decision = AutomaticPolicy.decide(new BigDecimal(currentPayment.amount()), new BigDecimal(currentPayment.allocated()),
                    currentPayment.targetInvoiceSource(), currentPayment.targetExternalInvoiceId(), true, balances.getFirst().remaining());
            if (!decision.eligible()) throw new AutomaticNotApplied(paymentId, decision);
        }
        BigDecimal requested = lockedCommand.allocations().stream().map(AllocationCommand.Target::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (requested.compareTo(paymentRemaining) > 0) throw ApiException.conflict("Allocation exceeds payment remaining amount");
        for (int index = 0; index < balances.size(); index++) {
            if (lockedCommand.allocations().get(index).amount().compareTo(balances.get(index).remaining()) > 0)
                throw ApiException.conflict("Allocation exceeds invoice remaining amount");
        }

        var ids = new ArrayList<Long>(lockedCommand.allocations().size());
        var auditAllocations = new ArrayList<AuditAllocation>(lockedCommand.allocations().size());
        for (var target : lockedCommand.allocations()) {
            long id = jdbc.queryForObject("INSERT INTO allocations(operation_id,payment_id,invoice_id,amount,origin) VALUES(?,?,?,?,?) RETURNING id", Long.class,
                    operation, paymentId, target.invoiceId(), target.amount(), mode.origin);
            ids.add(id);
            observer.afterAllocationInsert(context, id, ids.size());
            auditAllocations.add(new AuditAllocation(id, paymentId, target.invoiceId(), Money.format(target.amount()), mode.origin));
        }
        jdbc.update("INSERT INTO audit_events(operation_id,actor,event_type,aggregate_id,detail) VALUES(?,?,'ALLOCATION_COMMITTED',?,?)",
                operation, mode.actor, "payment:" + paymentId, mapper.writeValueAsString(new AuditDetail(List.copyOf(auditAllocations))));
        var applied = new Applied(operation, List.copyOf(ids), queries.payment(paymentId),
                lockedCommand.allocations().stream().map(target -> queries.invoice(target.invoiceId())).toList());
        String responseBody = mapper.writeValueAsString(applied);
        int completed = jdbc.update("UPDATE idempotency_records SET response_status=201,response_body=? WHERE operation_type=? AND key=? AND operation_id=?",
                responseBody, mode.operationType, normalizedKey, operation);
        if (completed != 1) throw new IllegalStateException("Idempotency claim unexpectedly missing");
        observer.beforeSuccessfulCommit(context);
        return new Result(applied, new SavedResponse(201, responseBody, false));
    }
    private AllocationCommand automaticCommandUnderPaymentLock(long paymentId) {
        var payment = queries.payment(paymentId);
        var decision = AutomaticPolicy.paymentDecision(new BigDecimal(payment.amount()), new BigDecimal(payment.allocated()), payment.targetInvoiceSource(), payment.targetExternalInvoiceId());
        if (!decision.eligible()) throw new AutomaticNotApplied(paymentId, decision);
        // Immutable metadata only: target balances are not read until its parent lock is held.
        var targetIds = jdbc.queryForList("SELECT id FROM invoices WHERE source=? AND external_invoice_id=?", Long.class,
                payment.targetInvoiceSource(), payment.targetExternalInvoiceId());
        if (targetIds.isEmpty()) throw new AutomaticNotApplied(paymentId, AutomaticPolicy.decide(new BigDecimal(payment.amount()), new BigDecimal(payment.allocated()),
                payment.targetInvoiceSource(), payment.targetExternalInvoiceId(), false, null));
        return new AllocationCommand(paymentId, List.of(new AllocationCommand.Target(targetIds.getFirst(), new BigDecimal(payment.amount())))).canonicalized();
    }
    public static String normalizeKey(String key) {
        if (key == null) throw ApiException.structure("Missing Idempotency-Key");
        String trimmed = key.strip();
        int length = trimmed.codePointCount(0, trimmed.length());
        if (length < 1 || length > 200) throw ApiException.structure("Idempotency-Key must be 1–200 characters");
        return trimmed;
    }
    private void lock(Context context, Parent parent, long id) {
        String table = parent == Parent.PAYMENT ? "payments" : "invoices";
        observer.beforeParentLock(context, parent, id);
        // Table is an internal constant; all external values are bound parameters.
        var ids=jdbc.queryForList("SELECT id FROM " + table + " WHERE id=? FOR UPDATE",Long.class,id);
        if (ids.isEmpty()) throw ApiException.missing("Allocation parent not found");
        observer.afterParentLock(context, parent, id);
    }
}
