package com.example.banking.persistence;

import com.example.banking.domain.AccountBalance;
import com.example.banking.domain.BankingCommand;
import com.example.banking.domain.Money;
import com.example.banking.domain.OperationResult;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class BankingStore {
    private final JdbcTemplate jdbc;

    public void claim(BankingCommand command) {
        String requestId = command.getRequestId().toString();
        String commandIdentity = command.identity();
        try {
            jdbc.update("INSERT INTO operations(request_id, command_identity, outcome) VALUES (?, ?, 'PROCESSING')",
                    requestId, commandIdentity);
        } catch (DuplicateKeyException exception) {
            // This INSERT has exactly one unique constraint: the request ID primary key.
            throw new RequestAlreadyClaimed(exception);
        }
    }

    public Optional<StoredOperation> findOperation(UUID requestId) {
        List<StoredOperation> operations = jdbc.query(
                "SELECT * FROM operations WHERE request_id = ?", this::mapOperation, requestId.toString());
        if (operations.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(operations.get(0));
    }

    public Optional<AccountBalance> findAccount(UUID accountId) {
        return queryAccount("SELECT account_id, balance_minor FROM accounts WHERE account_id = ?", accountId);
    }

    public Optional<AccountBalance> lockAccount(UUID accountId) {
        return queryAccount("SELECT account_id, balance_minor FROM accounts WHERE account_id = ? FOR UPDATE", accountId);
    }

    public boolean createAccount(UUID accountId, Money initialDeposit) {
        String accountIdValue = accountId.toString();
        long initialBalance = initialDeposit.getMinorUnits();
        try {
            jdbc.update("INSERT INTO accounts(account_id, currency, balance_minor) VALUES (?, 'HKD', ?)",
                    accountIdValue, initialBalance);
            return true;
        } catch (DuplicateKeyException exception) {
            // InnoDB rolls back this failed statement; the transaction can save the rejection.
            return false;
        }
    }

    public void updateBalance(AccountBalance account) {
        String accountId = account.getAccountId().toString();
        long balance = account.getMinorUnits();
        int affectedRows = jdbc.update("UPDATE accounts SET balance_minor = ? WHERE account_id = ?",
                balance, accountId);
        requireOne(affectedRows);
    }

    public void addMovement(UUID requestId, AccountBalance account, long signedAmount) {
        String requestIdValue = requestId.toString();
        String accountId = account.getAccountId().toString();
        long balanceAfter = account.getMinorUnits();
        jdbc.update("""
                INSERT INTO account_movements(request_id, account_id, amount_minor, balance_after)
                VALUES (?, ?, ?, ?)
                """, requestIdValue, accountId, signedAmount, balanceAfter);
    }

    public void complete(OperationResult result) {
        String requestId = result.getRequestId().toString();
        String outcome = result.getOutcome().name();
        String accountId = result.getAccount().map(account -> account.getAccountId().toString()).orElse(null);
        Long accountBalance = result.getAccount().map(AccountBalance::getMinorUnits).orElse(null);
        String destinationId = result.getDestination()
                .map(account -> account.getAccountId().toString()).orElse(null);
        Long destinationBalance = result.getDestination().map(AccountBalance::getMinorUnits).orElse(null);

        int affectedRows = jdbc.update("""
                UPDATE operations
                SET outcome = ?, account_id = ?, account_balance = ?, destination_id = ?,
                    destination_balance = ?, completed_at = CURRENT_TIMESTAMP(6)
                WHERE request_id = ? AND outcome = 'PROCESSING'
                """, outcome, accountId, accountBalance, destinationId, destinationBalance, requestId);
        requireOne(affectedRows);
    }

    private Optional<AccountBalance> queryAccount(String sql, UUID accountId) {
        List<AccountBalance> accounts = jdbc.query(sql, this::mapAccount, accountId.toString());
        if (accounts.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(accounts.get(0));
    }

    private AccountBalance mapAccount(ResultSet row, int rowNumber) throws SQLException {
        UUID accountId = UUID.fromString(row.getString("account_id"));
        return new AccountBalance(accountId, row.getLong("balance_minor"));
    }

    private StoredOperation mapOperation(ResultSet row, int rowNumber) throws SQLException {
        UUID requestId = UUID.fromString(row.getString("request_id"));
        String commandIdentity = row.getString("command_identity");
        OperationResult.Outcome outcome = OperationResult.Outcome.valueOf(row.getString("outcome"));
        Optional<AccountBalance> account = snapshot(row, "account_id", "account_balance");
        Optional<AccountBalance> destination = snapshot(row, "destination_id", "destination_balance");
        OperationResult result = new OperationResult(requestId, outcome, account, destination);
        return new StoredOperation(commandIdentity, result);
    }

    private static Optional<AccountBalance> snapshot(ResultSet row, String idColumn, String amountColumn)
            throws SQLException {
        String accountId = row.getString(idColumn);
        if (accountId == null) {
            return Optional.empty();
        }
        return Optional.of(new AccountBalance(UUID.fromString(accountId), row.getLong(amountColumn)));
    }

    private static void requireOne(int affectedRows) {
        if (affectedRows != 1) {
            throw new IllegalStateException("Expected exactly one row; got " + affectedRows);
        }
    }
}
