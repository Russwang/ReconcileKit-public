package dev.reconcilekit.importbatch;

import dev.reconcilekit.api.ApiException;

/** HTTP-safe final failure; report persistence is explicit and may fail during a DB outage. */
public final class ImportFailure extends ApiException {
    private final ImportReport report;
    private final boolean attemptSaved;
    private final boolean outcomeUnknown;
    ImportFailure(int status, String code, String message, ImportReport report, boolean attemptSaved, boolean outcomeUnknown) {
        super(status, code, message); this.report = report; this.attemptSaved = attemptSaved; this.outcomeUnknown = outcomeUnknown;
    }
    public ImportReport report() { return report; }
    public boolean attemptSaved() { return attemptSaved; }
    public boolean outcomeUnknown() { return outcomeUnknown; }
}
