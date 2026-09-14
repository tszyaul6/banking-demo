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
    private final Money balance;

    public AccountBalance(UUID accountId, Money balance) {
        this.accountId = Objects.requireNonNull(accountId, "accountId");
        this.balance = Objects.requireNonNull(balance, "balance");
    }
}
