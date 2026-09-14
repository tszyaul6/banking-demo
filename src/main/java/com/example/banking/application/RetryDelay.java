package com.example.banking.application;

import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

@Component
public class RetryDelay {
    public void pause(int failedAttempt) throws InterruptedException {
        long upperBoundMillis = 25L << (failedAttempt - 1);
        Thread.sleep(ThreadLocalRandom.current().nextLong(upperBoundMillis + 1));
    }
}
