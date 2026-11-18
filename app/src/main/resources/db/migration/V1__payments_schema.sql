-- uuidv7() requires PostgreSQL 18+. Time-ordered ids keep B-tree inserts append-only.

CREATE TABLE account (
    id          uuid PRIMARY KEY DEFAULT uuidv7(),
    owner_id    varchar(64)   NOT NULL,
    holder_name varchar(120)  NOT NULL,
    currency    char(3)       NOT NULL DEFAULT 'BRL',
    balance     numeric(19, 2) NOT NULL CHECK (balance >= 0),
    version     bigint        NOT NULL DEFAULT 0,
    created_at  timestamptz   NOT NULL DEFAULT now()
);

CREATE INDEX idx_account_owner ON account (owner_id);

CREATE TABLE transfer (
    id                uuid PRIMARY KEY DEFAULT uuidv7(),
    requested_by      varchar(64)    NOT NULL,
    idempotency_key   varchar(64)    NOT NULL,
    source_account_id uuid           NOT NULL REFERENCES account (id),
    target_account_id uuid           NOT NULL REFERENCES account (id),
    amount            numeric(19, 2) NOT NULL CHECK (amount > 0),
    currency          char(3)        NOT NULL,
    status            varchar(20)    NOT NULL CHECK (status IN ('COMPLETED', 'REJECTED', 'PENDING_REVIEW')),
    risk_score        integer CHECK (risk_score BETWEEN 0 AND 100),
    decision_reason   varchar(120)   NOT NULL,
    created_at        timestamptz    NOT NULL DEFAULT now(),
    CONSTRAINT uq_transfer_idempotency UNIQUE (requested_by, idempotency_key),
    CONSTRAINT ck_transfer_distinct_accounts CHECK (source_account_id <> target_account_id)
);

CREATE INDEX idx_transfer_source ON transfer (source_account_id, created_at DESC);

CREATE TABLE ledger_entry (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transfer_id   uuid           NOT NULL REFERENCES transfer (id),
    account_id    uuid           NOT NULL REFERENCES account (id),
    direction     varchar(6)     NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    amount        numeric(19, 2) NOT NULL CHECK (amount > 0),
    balance_after numeric(19, 2) NOT NULL,
    created_at    timestamptz    NOT NULL DEFAULT now()
);

CREATE INDEX idx_ledger_account_created ON ledger_entry (account_id, created_at DESC);
