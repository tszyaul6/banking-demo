package com.example.banking.persistence;

import com.example.banking.domain.OperationResult;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;

@Getter
@EqualsAndHashCode
@ToString
@RequiredArgsConstructor
public final class StoredOperation {
    private final String commandIdentity;
    private final OperationResult result;
}
