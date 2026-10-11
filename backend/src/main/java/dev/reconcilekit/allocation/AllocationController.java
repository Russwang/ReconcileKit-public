package dev.reconcilekit.allocation;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/allocations")
public class AllocationController {
    private final AllocationService service;
    public AllocationController(AllocationService service) { this.service = service; }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> allocate(@RequestBody JsonNode body, @RequestHeader("Idempotency-Key") String key) {
        var saved = service.allocate(AllocationCommand.parse(body, 20), key);
        var response = ResponseEntity.status(saved.status()).contentType(MediaType.APPLICATION_JSON);
        if (saved.replayed()) response.header("Idempotency-Replayed", "true");
        return response.body(saved.body());
    }
}
