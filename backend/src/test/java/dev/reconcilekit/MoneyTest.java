package dev.reconcilekit;

import dev.reconcilekit.api.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MoneyTest {
    @Test void exactNormalizationAndBounds() {
        for (String input : new String[]{"60", "60.0", "60.00", "60.0000"})
            assertEquals("60.00", Money.format(Money.parse(input)));
        assertEquals("0.01", Money.format(Money.parse("0.01")));
        assertEquals("999999999999.99", Money.format(Money.parse("999999999999.99")));
    }
    @Test void neverRoundOrAcceptNonPlainPositiveValues() {
        for (String input : new String[]{"0", "-1", "0.001", "1000000000000", "NaN", "Infinity", "1e2", "£60", "", " 60", "+60"})
            assertThrows(ApiException.class, () -> Money.parse(input), input);
        assertThrows(ApiException.class, () -> Money.parse(null));
    }
}
