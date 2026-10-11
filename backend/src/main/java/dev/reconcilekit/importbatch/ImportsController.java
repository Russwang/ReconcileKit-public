package dev.reconcilekit.importbatch;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
public class ImportsController {
    private final ImportOrchestrator orchestrator;
    private final ImportBatchQueries queries;
    public ImportsController(ImportOrchestrator orchestrator, ImportBatchQueries queries) { this.orchestrator = orchestrator; this.queries = queries; }
    @PostMapping(path = "/api/v1/imports/invoices", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ImportReport> invoices(@RequestBody JsonNode body, HttpServletRequest request) {
        return ResponseEntity.status(201).body(orchestrator.importBatch(ImportCommand.Kind.INVOICES, body, String.valueOf(request.getAttribute("request_id"))));
    }
    @PostMapping(path = "/api/v1/imports/payments", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ImportReport> payments(@RequestBody JsonNode body, HttpServletRequest request) {
        return ResponseEntity.status(201).body(orchestrator.importBatch(ImportCommand.Kind.PAYMENTS, body, String.valueOf(request.getAttribute("request_id"))));
    }
    @GetMapping(path = "/api/v1/import-batches/{attemptId}", produces = MediaType.APPLICATION_JSON_VALUE)
    ImportReport batch(@PathVariable UUID attemptId) { return queries.report(attemptId); }
}
