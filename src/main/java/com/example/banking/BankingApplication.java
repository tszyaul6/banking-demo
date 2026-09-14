package com.example.banking;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class BankingApplication {
    public static void main(String[] args) {
        try (ConfigurableApplicationContext context = SpringApplication.run(BankingApplication.class, args)) {
            // This module's demo is finite; closing the context also closes the connection pool.
        }
    }
}
