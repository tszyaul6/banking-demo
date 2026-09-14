package com.example.banking.domain;

import java.util.Optional;
import java.util.UUID;

public interface BankingCommand {
    UUID getRequestId();

    UUID getAccountId();

    Money getAmount();

    Type getType();

    default Optional<UUID> getDestinationId() {
        return Optional.empty();
    }

    default String identity() {
        String destination = getDestinationId().map(UUID::toString).orElse("");
        return "v1|" + getType() + "|" + getAccountId() + "|" + destination + "|"
                + getAmount().getCurrency().getCurrencyCode() + "|" + getAmount().getMinorUnits();
    }

    enum Type {
        CREATE_ACCOUNT,
        DEPOSIT,
        WITHDRAWAL,
        TRANSFER
    }
}
