package snack;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** An amount in one currency. Exact decimals, and two currencies can never be mixed by accident. */
public record Money(BigDecimal amount, String currency) {

    public Money {
        Objects.requireNonNull(currency, "currency");
        amount = Objects.requireNonNull(amount, "amount").setScale(2, RoundingMode.HALF_EVEN);
    }

    public static Money of(String amount, String currency) {
        return new Money(new BigDecimal(amount), currency);
    }

    public static Money zero(String currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    public boolean lessThan(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount) < 0;
    }

    public Money min(Money other) {
        return lessThan(other) ? this : other;
    }

    /** Only used for the combined management report; budgets are always checked in the office's own currency. */
    public Money convert(BigDecimal rate, String toCurrency) {
        return new Money(amount.multiply(rate), toCurrency);
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("Cannot mix " + currency + " and " + other.currency);
        }
    }

    @Override
    public String toString() {
        return amount.toPlainString() + " " + currency;
    }
}
