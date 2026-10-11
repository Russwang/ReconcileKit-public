package dev.reconcilekit.allocation;

import java.math.BigDecimal;
import java.util.*;
import dev.reconcilekit.api.*;
import tools.jackson.databind.JsonNode;

public record AllocationCommand(long paymentId, List<Target> allocations) {
    public record Target(long invoiceId, BigDecimal amount) {}
    /** Validate service callers as well as JSON callers before any row lock is requested. */
    public AllocationCommand canonicalized() {
        if (paymentId <= 0) throw ApiException.structure("payment_id must be a positive integer");
        if (allocations == null || allocations.isEmpty() || allocations.size() > 20)
            throw ApiException.structure("Expected 1–20 allocation targets");
        var result = new ArrayList<Target>(allocations.size());
        var seen = new HashSet<Long>();
        for (Target target : allocations) {
            if (target == null || target.invoiceId() <= 0) throw ApiException.structure("invoice_id must be a positive integer");
            if (!seen.add(target.invoiceId())) throw ApiException.structure("Duplicate invoice target");
            if (target.amount() == null) throw ApiException.structure("Missing allocation amount");
            result.add(new Target(target.invoiceId(), Money.parse(target.amount().toPlainString())));
        }
        result.sort(Comparator.comparingLong(Target::invoiceId));
        return new AllocationCommand(paymentId, List.copyOf(result));
    }
    public static AllocationCommand parse(JsonNode input, int maxTargets) {
        JsonInput.object(input, Set.of("payment_id", "allocations"));
        long payment = JsonInput.positiveId(input, "payment_id");
        JsonNode targets = input.get("allocations");
        if (targets == null || !targets.isArray() || targets.size() < 1 || targets.size() > maxTargets)
            throw ApiException.structure("Expected 1–" + maxTargets + " allocation targets");
        var result = new ArrayList<Target>();
        var seen = new HashSet<Long>();
        for (JsonNode target : targets) {
            JsonInput.object(target, Set.of("invoice_id", "amount"));
            long invoice = JsonInput.positiveId(target, "invoice_id");
            if (!seen.add(invoice)) throw ApiException.structure("Duplicate invoice target");
            result.add(new Target(invoice, Money.parse(JsonInput.text(target, "amount"))));
        }
        result.sort(Comparator.comparingLong(Target::invoiceId));
        return new AllocationCommand(payment, List.copyOf(result)).canonicalized();
    }
}
