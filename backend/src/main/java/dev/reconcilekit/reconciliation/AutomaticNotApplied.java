package dev.reconcilekit.reconciliation;

/** Internal control flow: leaving the proxy rolls back its claim before a run reports no write. */
public final class AutomaticNotApplied extends RuntimeException {
    public enum Outcome { SKIPPED, REVIEW }
    private final long paymentId;
    private final AutomaticPolicy.Decision decision;
    public AutomaticNotApplied(long paymentId, AutomaticPolicy.Decision decision) {
        super("Automatic allocation did not apply"); this.paymentId = paymentId; this.decision = decision;
    }
    public long paymentId() { return paymentId; }
    public Outcome outcome() { return decision.fullyAllocated() ? Outcome.SKIPPED : Outcome.REVIEW; }
    public String reason() { return decision.reason(); }
}
