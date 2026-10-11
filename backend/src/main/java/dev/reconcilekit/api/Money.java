package dev.reconcilekit.api;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class Money {
    public static final BigDecimal MAX = new BigDecimal("999999999999.99");
    private Money() {}
    public static BigDecimal parse(String input) {
        if (input == null || !input.matches("[0-9]+(?:\\.[0-9]+)?"))
            throw ApiException.invalid("Amount must be a plain decimal string");
        try {
            BigDecimal amount = new BigDecimal(input).setScale(2, RoundingMode.UNNECESSARY);
            if (amount.signum() <= 0 || amount.compareTo(MAX) > 0) throw ApiException.invalid("Amount outside supported range");
            return amount;
        } catch (ArithmeticException | NumberFormatException e) {
            throw ApiException.invalid("Amount must contain exact pennies without rounding");
        }
    }
    public static String format(BigDecimal amount) { return amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString(); }
}
