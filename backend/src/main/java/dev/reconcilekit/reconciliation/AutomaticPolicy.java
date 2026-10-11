package dev.reconcilekit.reconciliation;

import java.math.BigDecimal;

/** The same pure eligibility policy serves locked writes and read-only pending reasons. */
public final class AutomaticPolicy {
    private AutomaticPolicy() {}
    public record Decision(boolean fullyAllocated, String reason) {
        public boolean eligible() { return !fullyAllocated && reason == null; }
    }
    public static Decision paymentDecision(BigDecimal amount, BigDecimal allocated, String targetSource, String targetId) {
        if (allocated.compareTo(amount) == 0) return new Decision(true, null);
        if (allocated.signum() > 0) return new Decision(false, "MANUAL_REQUIRED");
        if (targetSource == null || targetId == null) return new Decision(false, "NO_EXPLICIT_TARGET");
        return new Decision(false, null);
    }
    public static Decision decide(BigDecimal amount, BigDecimal allocated, String targetSource, String targetId,
            boolean targetExists, BigDecimal targetRemaining) {
        Decision payment = paymentDecision(amount, allocated, targetSource, targetId);
        if (!payment.eligible()) return payment;
        if (!targetExists) return new Decision(false, "NO_TARGET");
        if (targetRemaining.signum() == 0) return new Decision(false, "TARGET_ALREADY_PAID");
        if (amount.compareTo(targetRemaining) > 0) return new Decision(false, "OVERPAYMENT");
        return new Decision(false, null);
    }
}
