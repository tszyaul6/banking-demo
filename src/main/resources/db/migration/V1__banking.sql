CREATE TABLE accounts (
    account_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    currency CHAR(3) CHARACTER SET ascii NOT NULL,
    balance_minor BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT ck_account_balance CHECK (balance_minor >= 0),
    CONSTRAINT ck_account_currency CHECK (currency = 'HKD')
) ENGINE = InnoDB;

CREATE TABLE operations (
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    command_identity VARCHAR(256) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    outcome VARCHAR(40) NOT NULL,
    account_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    account_balance BIGINT NULL,
    destination_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    destination_balance BIGINT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    completed_at TIMESTAMP(6) NULL,
    CONSTRAINT ck_operation_outcome CHECK (outcome IN (
        'PROCESSING', 'SUCCEEDED', 'INSUFFICIENT_FUNDS', 'ACCOUNT_NOT_FOUND',
        'ACCOUNT_ALREADY_EXISTS', 'BALANCE_LIMIT_EXCEEDED')),
    CONSTRAINT ck_operation_account CHECK (
        (account_id IS NULL AND account_balance IS NULL) OR
        (account_id IS NOT NULL AND account_balance IS NOT NULL AND account_balance >= 0)),
    CONSTRAINT ck_operation_destination CHECK (
        (destination_id IS NULL AND destination_balance IS NULL) OR
        (destination_id IS NOT NULL AND destination_balance IS NOT NULL AND destination_balance >= 0))
) ENGINE = InnoDB;

CREATE TABLE account_movements (
    movement_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    amount_minor BIGINT NOT NULL,
    balance_after BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uq_movement_operation_account UNIQUE (request_id, account_id),
    CONSTRAINT fk_movement_operation FOREIGN KEY (request_id) REFERENCES operations(request_id),
    CONSTRAINT fk_movement_account FOREIGN KEY (account_id) REFERENCES accounts(account_id),
    CONSTRAINT ck_movement_amount CHECK (amount_minor <> 0),
    CONSTRAINT ck_movement_balance CHECK (balance_after >= 0),
    INDEX ix_movement_account (account_id, movement_id)
) ENGINE = InnoDB;
