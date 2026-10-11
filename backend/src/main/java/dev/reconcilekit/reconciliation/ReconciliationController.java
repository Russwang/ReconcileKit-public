package dev.reconcilekit.reconciliation;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
public class ReconciliationController {
    private final ReconciliationRunService runs;
    public ReconciliationController(ReconciliationRunService runs) { this.runs = runs; }
    @PostMapping(path = "/api/v1/reconciliation/runs", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ReconciliationRunService.RunReport run(@RequestBody JsonNode body) { return runs.run(ReconciliationRunCommand.parse(body)); }
}
