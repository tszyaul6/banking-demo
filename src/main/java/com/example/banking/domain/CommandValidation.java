package com.example.banking.domain;

import java.util.Objects;
import java.util.UUID;

final class CommandValidation {
    private CommandValidation() {
    }

    static void requireFields(UUID requestId, UUID accountId, Money amount) {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(amount, "amount");
    }

    static void requirePositiveAmount(Money amount) {
        if (amount.getMinorUnits() == 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
    }
}
