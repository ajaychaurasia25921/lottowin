CREATE TABLE IF NOT EXISTS gameboard_results (
    board_id VARCHAR(64) PRIMARY KEY,
    capacity INTEGER NOT NULL CHECK (capacity IN (5, 10, 15, 20)),
    entry_fee_coins BIGINT NOT NULL CHECK (entry_fee_coins > 0),
    total_pool_coins BIGINT NOT NULL CHECK (total_pool_coins >= 0),
    platform_fee_coins BIGINT NOT NULL CHECK (platform_fee_coins >= 0),
    winner_payout_coins BIGINT NOT NULL CHECK (winner_payout_coins >= 0),
    winning_number INTEGER,
    winner_user_id VARCHAR(128),
    completed_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_gameboard_results_winner_completed
    ON gameboard_results (winner_user_id, completed_at DESC);
