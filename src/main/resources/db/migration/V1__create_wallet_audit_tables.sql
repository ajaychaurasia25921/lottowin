CREATE TABLE IF NOT EXISTS wallet_ledger (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL,
    amount_coins BIGINT NOT NULL CHECK (amount_coins > 0),
    balance_after_coins BIGINT NOT NULL CHECK (balance_after_coins >= 0),
    direction VARCHAR(16) NOT NULL CHECK (direction IN ('CREDIT', 'DEBIT')),
    reason VARCHAR(64) NOT NULL,
    reference_id VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_wallet_ledger_user_created
    ON wallet_ledger (user_id, created_at DESC);

CREATE TABLE IF NOT EXISTS payment_transactions (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL,
    razorpay_order_id VARCHAR(128) NOT NULL UNIQUE,
    razorpay_payment_id VARCHAR(128),
    coins BIGINT NOT NULL CHECK (coins > 0),
    amount_paise BIGINT NOT NULL CHECK (amount_paise > 0),
    currency CHAR(3) NOT NULL DEFAULT 'INR',
    status VARCHAR(32) NOT NULL CHECK (status IN ('CREATED', 'VERIFIED')),
    sandbox BOOLEAN NOT NULL DEFAULT TRUE,
    receipt VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    verified_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_payment_transactions_user_status
    ON payment_transactions (user_id, status);
