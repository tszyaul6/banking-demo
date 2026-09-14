package com.example.banking.persistence;

public final class RequestAlreadyClaimed extends RuntimeException {
    public RequestAlreadyClaimed(Throwable cause) {
        super("Request ID has already been claimed", cause);
    }
}
