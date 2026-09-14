package com.example.banking.application;

import lombok.Getter;

import java.util.UUID;

@Getter
public class BankingException extends RuntimeException {
    private final UUID requestId;

    public BankingException(UUID requestId, String message, Throwable cause) {
        super(message, cause);
        this.requestId = requestId;
    }

    public static final class IdempotencyConflict extends BankingException {
        public IdempotencyConflict(UUID requestId) {
            super(requestId, "Request ID was already used for a different command", null);
        }
    }

    public static final class TemporarilyUnavailable extends BankingException {
        public TemporarilyUnavailable(UUID requestId, Throwable cause) {
            super(requestId, "Attempts exhausted; retry the same request ID", cause);
        }
    }

    public static final class OutcomeUnknown extends BankingException {
        public OutcomeUnknown(UUID requestId, Throwable cause) {
            super(requestId, "Outcome is unknown; query or replay the same request ID", cause);
        }
    }
}
