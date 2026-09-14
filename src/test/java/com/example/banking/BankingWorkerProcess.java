package com.example.banking;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

final class BankingWorkerProcess implements AutoCloseable {
    private final Process process;
    private final BufferedReader reader;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    private final Future<List<String>> output;

    private BankingWorkerProcess(Process process) {
        this.process = process;
        this.reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        this.output = executor.submit(this::readOutput);
    }

    static BankingWorkerProcess start(String jdbcUrl, String username, String password,
                                      UUID accountId, UUID requestId) throws IOException {
        Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), ProcessBankingWorker.class.getName(),
                jdbcUrl, username, password, accountId.toString(), requestId.toString())
                .redirectErrorStream(true).start();
        return new BankingWorkerProcess(process);
    }

    void awaitReady() throws Exception {
        ready.get(30, TimeUnit.SECONDS);
    }

    void release() throws IOException {
        process.getOutputStream().write("GO\n".getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().flush();
    }

    List<String> awaitOutput() throws Exception {
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Banking worker did not exit within 20 seconds");
        }
        List<String> lines = output.get(5, TimeUnit.SECONDS);
        if (process.exitValue() != 0) {
            throw new IllegalStateException("Banking worker failed: " + lines);
        }
        return lines;
    }

    private List<String> readOutput() throws IOException {
        List<String> lines = new ArrayList<>();
        try {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                lines.add(line);
                if (line.equals("READY")) {
                    ready.complete(null);
                }
            }
            ready.completeExceptionally(new IllegalStateException("Worker exited before ready: " + lines));
            return List.copyOf(lines);
        } catch (IOException exception) {
            ready.completeExceptionally(exception);
            throw exception;
        }
    }

    @Override
    public void close() throws Exception {
        try {
            process.destroyForcibly();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Banking worker did not terminate");
            }
        } finally {
            try {
                reader.close();
            } finally {
                executor.shutdownNow();
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Banking worker output reader did not terminate");
                }
            }
        }
    }
}
