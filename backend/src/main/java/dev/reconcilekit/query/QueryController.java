package dev.reconcilekit.query;

import dev.reconcilekit.reconciliation.ReconciliationQueries;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class QueryController {
    private final LedgerQueries queries;
    private final ReconciliationQueries reconciliation;
    public QueryController(LedgerQueries queries, ReconciliationQueries reconciliation) { this.queries = queries; this.reconciliation = reconciliation; }
    @GetMapping("/invoices") Object invoices(@RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="50") int size) { return queries.invoices(page,size); }
    @GetMapping("/payments") Object payments(@RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="50") int size) { return queries.payments(page,size,false); }
    @GetMapping("/reconciliation") Object pending(@RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="50") int size) { return reconciliation.pending(page,size); }
}
