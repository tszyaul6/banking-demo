package com.example.banking.domain;

import lombok.EqualsAndHashCode;
import lombok.Getter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

@Getter
@EqualsAndHashCode
public final class Money {
    public static final Currency HKD = Currency.getInstance("HKD");

    private final long minorUnits;
    private final Currency currency;

    public Money(long minorUnits, Currency currency) {
        Objects.requireNonNull(currency, "currency");
        if (!HKD.equals(currency)) {
            throw new IllegalArgumentException("Only HKD is supported");
        }
        if (minorUnits < 0) {
            throw new IllegalArgumentException("Money cannot be negative");
        }
        this.minorUnits = minorUnits;
        this.currency = currency;
    }

    public static Money hkd(String decimalAmount) {
        Objects.requireNonNull(decimalAmount, "decimalAmount");
        try {
            BigDecimal decimal = new BigDecimal(decimalAmount).setScale(2, RoundingMode.UNNECESSARY);
            return new Money(decimal.movePointRight(2).longValueExact(), HKD);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Amount must fit in whole HKD cents", exception);
        }
    }

    public static Money cents(long amount) {
        return new Money(amount, HKD);
    }

    public BigDecimal getDecimalAmount() {
        return BigDecimal.valueOf(minorUnits, 2);
    }

    @Override
    public String toString() {
        return currency.getCurrencyCode() + " " + getDecimalAmount().toPlainString();
    }
}
