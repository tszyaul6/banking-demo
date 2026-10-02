package com.example.banking.domain;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.util.Objects;
import java.util.UUID;

@Getter
@EqualsAndHashCode
@ToString
public final class AccountBalance {
    private final UUID accountId;
    private final long minorUnits;

    public AccountBalance(UUID accountId, long minorUnits) {
        this.accountId = Objects.requireNonNull(accountId, "accountId");
        this.minorUnits = minorUnits;
    }
}
