package dev.reconcilekit.audit;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/audit-events")
public class AuditController {
    private final AuditQueries queries;
    public AuditController(AuditQueries queries) { this.queries = queries; }
    @GetMapping Object events(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return queries.events(page, size);
    }
}
