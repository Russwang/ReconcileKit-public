package dev.reconcilekit.allocation;

import dev.reconcilekit.api.Money;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Versioned semantic encoding: only numeric IDs and normalized decimal amounts enter the hash. */
public final class AllocationRequestHash {
    private AllocationRequestHash() {}
    public static String of(AllocationCommand normalized) {
        var encoding = new StringBuilder("manual-allocation:v1\npayment:").append(normalized.paymentId()).append('\n');
        for (var target : normalized.allocations())
            encoding.append("invoice:").append(target.invoiceId()).append(":amount:").append(Money.format(target.amount())).append('\n');
        return hash(encoding.toString());
    }
    /** Policy v1 is fully determined by an immutable payment; clients cannot choose its target. */
    public static String automatic(long paymentId) {
        return hash("auto-allocation:v1\npayment:" + paymentId + "\n");
    }
    private static String hash(String encoding) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoding.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Required SHA-256 unavailable", impossible);
        }
    }
}
