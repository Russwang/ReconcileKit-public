package dev.reconcilekit.reconciliation;

import dev.reconcilekit.api.ApiException;
import dev.reconcilekit.api.JsonInput;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;

public final class ReconciliationRunCommand {
    private ReconciliationRunCommand() {}
    public static List<Long> parse(JsonNode body) {
        JsonInput.object(body, Set.of("payment_ids"));
        JsonNode ids = body.get("payment_ids");
        if (ids == null || !ids.isArray()) throw ApiException.structure("payment_ids must be an array");
        if (ids.isEmpty() || ids.size() > 100) throw ApiException.invalid("payment_ids must contain 1–100 IDs");
        var result = new ArrayList<Long>(ids.size());
        for (JsonNode id : ids) {
            if (!id.isIntegralNumber() || !id.canConvertToLong() || id.asLong() <= 0)
                throw ApiException.structure("Each payment ID must be a positive integer");
            result.add(id.asLong());
        }
        return validate(result);
    }
    public static List<Long> validate(List<Long> ids) {
        if (ids == null) throw ApiException.structure("Missing payment IDs");
        if (ids.isEmpty() || ids.size() > 100) throw ApiException.invalid("payment_ids must contain 1–100 IDs");
        var seen = new HashSet<Long>();
        for (Long id : ids) {
            if (id == null || id <= 0) throw ApiException.structure("Each payment ID must be a positive integer");
            if (!seen.add(id)) throw ApiException.structure("Duplicate payment ID");
        }
        return List.copyOf(ids);
    }
}
